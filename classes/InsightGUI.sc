// =====================================================================
// InsightPanel - the analyser's face: a dashboard of titled panes
//
//   (o) INSIGHT  < streaming -14  v >  save  ...     freeze  reset  bus 0 · 2 ch v
//   +-- spectrum  [sum v] [+3 dB/oct v] fast|medium|slow  max  [fft 4096 v] --+ +-- levels · loudness --+
//   |                                                                           | |  L  R      M  S       |
//   |   now, a moment ago, the loudest held - log Hz, dB                        | |  |  |      |  |  ==== | target band
//   +---------------------------------------------------------------------------+ +-----------------------+
//   +-- loudness · short term  1 min|5 min --------+ +-- stereo -------+ +-- since reset · 01:23 ------+
//   |   target band, integrated dotted, the line   | |  goniometer  |c | |  -20.5 LUFS  6.5 LU under   |
//   +----------------------------------------------+ +-----------------+ |  short  true peak ...  (target) (ceiling)
//
// The frame of every effect window (sc-fx-ui) - FxHeader at the top, the
// panes where an effect has its display and meters, FxDeviceGUI's footer -
// with an analyser's face: no mix or output, since nothing passes through
// it, and the presets are loudness targets (Insight.presetNames), the one
// thing worth a name. The power button runs the analysis. Signal blue for
// levels and spectra, the same blue paler for loudness, outlines for the
// targets, red only for an over.
//
//   p = InsightPanel(Insight.at(\main));   // what InsightGUI shows
//   p.frozen = true;                       // hold the picture
//   p.running = false;                     // stop drawing (not the analysis)
// =====================================================================

InsightPanel {
	classvar <loud;

	var <view, <insight, <header;
	var <spectrumView, <meterView, <imagerView, <historyView, <readingsView;
	var <freezeSwitch, <resetButton, <busButton;
	var <sourceChoice, <tiltChoice, <speedSwitch, <holdSwitch, <fftChoice, <spanSwitch;
	var <targetKnob, <ceilingKnob, <readingsCard, <spectrumTitle;	// busButton: an FxChoice
	var <frozen = false, <running = false, <>tickAction;
	var ctrl, classCtrl, bmCtrl, updater, hints, busNames;

	*initClass {
		// loudness (LUFS): the signal's own blue, paler - the dBFS bars and the
		// spectrum keep the full blue, so the two scales never read as one
		loud = Color(0.74, 0.87, 0.98);
	}

	// within the target ±tolerance, or how far off it, in words
	*deltaText { |lufs, target|
		var d;
		if (lufs.isNil or: { lufs < -100 }) { ^"nothing measured yet" };
		d = lufs - target;
		^case
		{ d.abs <= Insight.tolerance } { "on the target" }
		{ d < 0 } { FxPalette.fixed(d.neg, 1) ++ " LU under the target" }
		{ true } { FxPalette.fixed(d, 1) ++ " LU over the target" }
	}

	// a target is drawn as an outline, never a colour: a faint band, its edges,
	// a dashed line through the middle
	*drawTarget { |rect|
		var y = (rect.top + (rect.height / 2)).round + 0.5;
		Pen.fillColor = FxPalette.fade(FxPalette.text, 0.06);
		Pen.fillRect(rect);
		Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.55);
		Pen.width = 1;
		Pen.addRect(rect.insetBy(0.5, 0.5));
		Pen.stroke;
		Pen.use {
			Pen.lineDash = FloatArray[4, 3];
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.8);
			Pen.line(rect.left @ y, rect.right @ y);
			Pen.stroke;
		};
	}

	*new { |insight, parent| ^super.new.init(insight, parent) }

	init { |argInsight, parent|
		var spectrumPane, historyPane, stereoPane, levelsPane, left, right, bottomH = 222;
		insight = argInsight;
		busNames = [];
		this.prMakeControls;
		header = FxHeader(insight, "insight", nil, [freezeSwitch.view, resetButton.view, busButton.view]);
		header.defaultsFunc = { |u| u.restoreDefaults };

		spectrumView = InsightSpectrumView(insight);
		meterView = InsightMeterView(insight);
		imagerView = InsightImagerView(insight);
		historyView = InsightHistoryView(insight);
		readingsView = InsightReadingsView(insight);

		spectrumTitle = this.prTitle("spectrum");
		spectrumPane = this.prPane(spectrumTitle,
			[sourceChoice, tiltChoice, speedSwitch, holdSwitch, fftChoice], spectrumView.view);
		historyPane = this.prPane(this.prTitle("loudness · short term"), [spanSwitch], historyView.view);
		stereoPane = this.prPane(this.prTitle("stereo"), [], imagerView.view);
		levelsPane = this.prPane(this.prTitle("levels · loudness"), [], meterView.view);
		readingsCard = FxCard("since reset", VLayout(
			readingsView.view,
			HLayout(nil, targetKnob.view, nil, ceilingKnob.view, nil).margins_(0).spacing_(0)
		).margins_(0).spacing_(6));
		readingsCard.titleView.minWidth_(220);

		[historyPane, stereoPane, readingsCard.view].do(_.fixedHeight_(bottomH));
		stereoPane.fixedWidth_(236);
		left = VLayout([spectrumPane, stretch: 1], HLayout([historyPane, stretch: 1], stereoPane).spacing_(8))
			.spacing_(8).margins_(0);
		right = View().fixedWidth_(292).layout_(VLayout([levelsPane, stretch: 1], readingsCard.view)
			.spacing_(8).margins_(0));
		view = View(parent).layout_(VLayout(
			header.view,
			[HLayout([left, stretch: 1], right).spacing_(8).margins_(0), stretch: 1]
		).margins_(0).spacing_(8));

		ctrl = SimpleController(insight)
			.put(\settings, { { this.refresh }.defer })
			.put(\running, { { this.refresh }.defer })
			.put(\bus, { { this.refresh }.defer })
			.put(\reset, { { this.prRedraw }.defer });
		classCtrl = SimpleController(Insight)
			.put(\presets, { { this.refresh }.defer })
			.put(\clipboard, { { this.refresh }.defer });
		\BusManager.asClass !? { |bm|
			bmCtrl = SimpleController(bm).put(\buses, { { this.refresh }.defer });
		};
		this.refresh;
		^this
	}

	// --------------------------------------------------------------- build

	prTitle { |str|
		^StaticText().string_(str).font_(FxPalette.font).stringColor_(FxPalette.text)
			.minWidth_((str.bounds(FxPalette.font).width + 6).ceil)
	}

	// a titled pane: a dark well, its title and any controls along the top
	prPane { |title, extras, body|
		var pane = UserView();
		pane.drawFunc = { |v|
			Pen.fillColor = FxPalette.waveBg;
			Pen.addRoundedRect(v.bounds.moveTo(0, 0), 4, 4);
			Pen.fill;
		};
		pane.layout = VLayout(
			HLayout(*([title, nil] ++ extras.collect(_.view))).spacing_(6).margins_(0),
			[body, stretch: 1]
		).margins_([8, 6, 8, 6]).spacing_(4);
		^pane
	}

	prMakeControls {
		var smooth = [0, 0.6, 0.85];
		freezeSwitch = FxSwitch(["freeze"], true).action_({ |s| this.frozen = s.value });
		resetButton = FxButton("reset", { insight.reset });
		// a menu button whose menu is made when it opens: the bus names change
		busButton = FxChoice(nil, ["bus"], 124);
		busButton.view.mouseDownAction = { this.prBusMenu; true };

		sourceChoice = FxChoice(nil, Insight.sources.collect { |s| (sum: "sum", left: "left", right: "right", mid: "mid", side: "side")[s] }, 70)
			.action_({ |c| insight.source = Insight.sources[c.value] });
		tiltChoice = FxChoice(nil, Insight.tilts.collect { |t| if (t == 0) { "no tilt" } { "+% dB/oct".format(t) } }, 104)
			.action_({ |c| insight.tilt = Insight.tilts[c.value] });
		speedSwitch = FxSwitch(["fast", "medium", "slow"]).action_({ |s| insight.smoothing = smooth[s.value] });
		holdSwitch = FxSwitch(["max"], true).action_({ |s| insight.hold = s.value });
		fftChoice = FxChoice(nil, Insight.fftSizes.collect { |n| "fft " ++ n }, 86)
			.action_({ |c| insight.fftSize = Insight.fftSizes[c.value] });
		spanSwitch = FxSwitch(["1 min", "5 min"]).action_({ |s| insight.historySeconds = [60, 300][s.value]; historyView.refresh });

		targetKnob = FxKnob("target", ControlSpec(-60, 0, \lin, 1, -14, "LUFS"));
		targetKnob.dec = 0;
		targetKnob.menuItems = Insight.targets.collect { |t| [t[0], t[1]] };
		targetKnob.action = { |k| insight.target = k.mapped };
		ceilingKnob = FxKnob("ceiling", ControlSpec(-20, 0, \lin, 0.1, -1, "dBTP"));
		ceilingKnob.dec = 1;
		ceilingKnob.menuItems = [["-0.1 dBTP", -0.1], ["-1 dBTP", -1], ["-2 dBTP", -2], ["-3 dBTP", -3]];
		ceilingKnob.action = { |k| insight.ceiling = k.mapped };
	}

	// ---------------------------------------------------------------- state

	frozen_ { |bool|
		frozen = bool == true;
		freezeSwitch.value = frozen;
		this.prRedraw;
	}

	// the drawing, not the analysis: the window starts and stops that
	running_ { |bool|
		running = bool == true;
		updater !? (_.stop);
		updater = nil;
		if (running) { this.prStartUpdater };
	}

	isClosed { ^view.isNil or: { view.isClosed } }

	remove {
		ctrl !? (_.remove);
		classCtrl !? (_.remove);
		bmCtrl !? (_.remove);
		ctrl = classCtrl = bmCtrl = nil;
		updater !? (_.stop);
		updater = nil;
	}

	widgets {
		^[freezeSwitch, resetButton, busButton, sourceChoice, tiltChoice, speedSwitch, holdSwitch,
			fftChoice, spanSwitch, targetKnob, ceilingKnob]
	}

	// every control from the insight, and the panes drawn again
	refresh {
		var muted;
		if (this.isClosed) { ^this };
		muted = insight.running.not;
		header.refresh;
		sourceChoice.value = Insight.sources.indexOf(insight.source) ? 0;
		tiltChoice.value = Insight.tilts.indexOf(insight.tilt) ? 2;
		speedSwitch.value = [0, 0.6, 0.85].detectIndex { |s| s >= (insight.smoothing - 0.01) } ? 2;
		holdSwitch.value = insight.hold;
		fftChoice.value = Insight.fftSizes.indexOf(insight.fftSize) ? 2;
		spanSwitch.value = if (insight.historySeconds > 120) { 1 } { 0 };
		targetKnob.mapped = insight.target;
		ceilingKnob.mapped = insight.ceiling;
		busButton.override = this.busText;
		busButton.refresh;
		this.widgets.do { |w| w.tryPerform(\muted_, muted) };
		this.prRedraw;
	}

	prRedraw {
		if (this.isClosed) { ^this };
		spectrumView.view.refresh;
		meterView.refresh;
		imagerView.view.refresh;
		historyView.refresh;
		readingsView.refresh;
		this.prRefreshCard;
	}

	prRefreshCard {
		readingsCard.title = if (insight.running) {
			"since reset · " ++ InsightGUI.time(insight.loudness.duration)
		} { "since reset · stopped" };
	}

	busText {
		var b = insight.bus;
		^(if (b.isNil) { insight.busName !? (_.asString) ? "no bus" } { insight.busName !? (_.asString) ? ("bus " ++ b) })
			++ " · " ++ insight.channels ++ " ch"
	}

	// ------------------------------------------------------------- the bus

	// BusManager's names (the insight follows one), a bus by number, how many channels
	prBusActions {
		var bm = \BusManager.asClass, actions = [];
		busNames = bm !? { bm.entries.select(_.isAudio).collect(_.name) } ? [];
		busNames.do { |n|
			actions = actions.add(MenuAction(n.asString, { insight.bus = n })
				.checkable_(true).checked_(insight.busName == n));
		};
		if (actions.notEmpty) { actions = actions.add(MenuAction.separator) };
		actions = actions.add(MenuAction("bus number...", { this.prBusNumberDialog }));
		actions = actions.add(MenuAction.separator);
		[1, 2, 4, 6, 8].do { |n|
			actions = actions.add(MenuAction(n.asString ++ if (n == 1) { " channel" } { " channels" },
				{ insight.channels = n }).checkable_(true).checked_(insight.channels == n));
		};
		^actions
	}

	prBusMenu { Menu(*this.prBusActions).front }

	prBusNumberDialog {
		var w, field, take;
		w = Window("insight reads bus...", Rect(300, 400, 300, 76), resizable: false);
		FxPalette.darken(w);
		field = TextField().font_(FxPalette.font).string_((insight.bus ? "").asString);
		take = {
			var s = field.string.stripWhiteSpace;
			if (s.notEmpty) { insight.bus = s.asInteger.max(0) };
			w.close;
		};
		field.action = take;
		w.layout = VLayout(
			StaticText().string_("a bus number - typing one stops following a name")
				.font_(FxPalette.smallFont).stringColor_(FxPalette.dim),
			HLayout(field, FxButton("read", take, 52).view).spacing_(6)
		).margins_(8).spacing_(6);
		w.front;
		field.focus(true);
	}

	// ---------------------------------------------------------------- hints

	hints_ { |bar|
		hints = bar;
		if (bar.isNil) { ^this };
		header.hints = bar;
		bar.attach(header.power.view, "run the analysis - off, it costs nothing and the panes hold still");
		bar.attach(header.presetChoice.view,
			"the loudness to aim at - a delivery's integrated loudness and true peak ceiling, then any you have saved; * once moved");
		bar.attach(freezeSwitch.view, "hold the picture to read a moment that has passed - the analysis carries on underneath");
		bar.attach(resetButton.view, "integrate again from now and drop every held maximum: peaks, overs, the held spectrum, the history");
		bar.attach(busButton.view, { "%  -  the bus it reads: a BusManager name (followed when it moves), a number, how many channels".format(this.busText) });
		bar.attach(sourceChoice.view, "what the spectrum is of: the sum of every channel, one side, the mid or the side");
		bar.attach(tiltChoice.view, "a slope added to the drawing, dB per octave round 1 kHz - at +3 pink noise reads flat");
		bar.attach(speedSwitch.view, "how much the spectrum is averaged over time - fast follows, slow settles");
		bar.attach(holdSwitch.view, "keep the loudest level reached in each band since reset - the dashed line");
		bar.attach(fftChoice.view, "the fft size - bigger resolves the low end better and moves slower");
		bar.attach(spanSwitch.view, "how much loudness history the pane shows");
		[spectrumView, meterView, imagerView, historyView, readingsView].do { |v| v.hints = bar };
		targetKnob.hints = bar;
		targetKnob.hint = "the integrated loudness to aim at - the outlined band in the history and the meters; a guide, not a limiter";
		ceilingKnob.hints = bar;
		ceilingKnob.hint = "the true peak not to pass - past it is an over, counted; a guide, not a limiter";
		bar.attach(targetKnob.view, { "target  % LUFS   -   %".format(InsightGUI.fmt(insight.target), targetKnob.hint) });
		bar.attach(ceilingKnob.view, { "ceiling  % dBTP   -   %".format(InsightGUI.fmt(insight.ceiling), ceilingKnob.hint) });
		bar.attach(readingsCard.view, "the numbers since the last reset");
	}

	hints { ^hints }

	// ------------------------------------------------------------- drawing

	prStartUpdater {
		updater = Routine({
			var tick = 0;
			loop {
				0.04.wait;
				if (this.isClosed) {
					updater.stop;
				} {
					spectrumView.refresh(frozen);
					if (frozen.not) {
						meterView.refresh;
						imagerView.refresh;
						if ((tick % 5) == 0) { historyView.refresh; readingsView.refresh };
					};
					if ((tick % 12) == 0) { this.prRefreshCard; tickAction.value(this) };
					tick = tick + 1;
				};
			};
		}).play(AppClock);
	}
}


// =====================================================================
// InsightGUI - an insight in a window of its own
//
//   Insight.at(\main).gui;      // or InsightGUI(insight, x, y)
//
// The panel above in FxDeviceGUI (sc EQ), the window every effect has: the
// hint at the left of one line along the bottom, what it reads at its right.
// Running follows the window - it starts when this opens and stops when it
// closes, unless it was already running before.
// =====================================================================

InsightGUI : FxDeviceGUI {
	var runWasOn = false, insCtrl;

	// one decimal, the sign kept, silence as the word: what every readout uses
	*fmt { |v|
		var r, neg;
		if (v.isNil) { ^"-" };
		if (v == -inf or: { v < -200 }) { ^"-inf" };
		if (v == inf) { ^"inf" };
		r = (v * 10).round.asInteger;
		neg = r < 0;
		r = r.abs;
		^(if (neg) { "-" } { "" }) ++ (r div: 10) ++ "." ++ (r % 10)
	}

	*time { |secs|
		var m = (secs / 60).floor.asInteger;
		var s = (secs - (m * 60)).floor.asInteger;
		^"%:%".format(m.asString.padLeft(2, "0"), s.asString.padLeft(2, "0"))
	}

	init { |argInsight, x, y|
		var closeBase;
		runWasOn = argInsight.running;
		argInsight.run;
		super.init(argInsight, x, y);
		hints.defaultString = "hover over a pane or a reading and this bar says what it measures";
		hints.clear;
		panel.tickAction = { this.prRefreshStatus };
		insCtrl = SimpleController(unit)
			.put(\running, { { this.prRefreshStatus }.defer })
			.put(\bus, { { this.prRefreshStatus }.defer });
		closeBase = window.onClose;
		window.onClose = {
			closeBase.value;
			insCtrl.remove;
			// leave running as we found it
			if (runWasOn.not) { unit.stop };
		};
	}

	insight { ^unit }
	frozen { ^panel.frozen }
	spectrumView { ^panel.spectrumView }
	meterView { ^panel.meterView }
	imagerView { ^panel.imagerView }
	historyView { ^panel.historyView }

	prRefreshStatus {
		var ins = unit, parts;
		if (window.isNil or: { window.isClosed }) { ^this };
		window.name = this.prTitle;
		parts = [ins.bus !? { "bus " ++ ins.bus } ? "no bus", ins.channels.asString ++ " ch"];
		if (ins.server.serverRunning) {
			ins.sampleRate !? { |sr| parts = parts.add(FxPalette.sig3(sr / 1000) ++ " kHz") };
			parts = parts.add(case
				{ ins.running.not } { "stopped" }
				{ ins.isAlive } { "running" }
				{ ins.isResolved.not } { "waiting for the bus" }
				{ true } { "starting" });
		} {
			parts = parts.add(if (ins.running) { "server not running" } { "stopped" });
		};
		statusText.string = parts.join(" · ");
	}
}


// The hint strip Insight had before it moved onto the shared frame (sc-fx-ui,
// 2026-11-09): FxHintBar under its old name, for code that made one.
InsightHintBar : FxHintBar {
	*new { |defaultString, height|
		^super.new(defaultString ? "hover over a pane or a reading and this bar says what it measures", height)
	}
}
