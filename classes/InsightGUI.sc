// =====================================================================
// InsightGUI - the window: spectrum, meters, loudness, image, history
//
//   insight  main   bus [0] [out1_2 v] ch [2]              [run] [freeze] [reset]
//   spectrum  source [sum v] res [4096 v] tilt [3 dB/oct v] [fast v] [x] max hold   target [-14] LUFS  ceiling [-1] dBTP
//   +----------------------------------------------------+ +--------------+
//   |  spectrum                                          | |  L  R   M S  |
//   |                                                    | |  |  |   | |  |
//   |   20   50  100  200  500  1k   2k   5k  10k  20k   | |  M  -18.3    |
//   +----------------------------------------------------+ |  S  -17.9    |
//   +----------------+ +-------------------------------+   |  I  -18.1    |
//   |   goniometer   | |  short term loudness, 60 s    |   |  LRA  6.2    |
//   |   corr / bal   | |                               |   |  TP  -0.8    |
//   +----------------+ +-------------------------------+   +--------------+
//   bus 0 · out1_2 · 2 ch · 48 kHz · fft 4096 · running · integrating 01:23
//
// The bus field is a number box and, with BusManager installed, a
// dropdown of its names next to it: pick a name and the insight follows
// it, type an index and it does not. Running follows the window - it
// starts when this opens and stops when it closes, unless it was
// already running before. Freeze stops the drawing, not the analysis.
// =====================================================================

InsightGUI {
	classvar <colBg, <colPanel, <colText, <colDim, <colAccent;
	classvar <colMeter, <colClip, <colOff, <colGrid, <colPeak, <colGood;

	var <insight, <window;
	var ctrl, bmCtrl, updater, runWasOn, lastStatus;
	var <frozen = false;
	var busBox, busMenu, chanBox, runBtn, freezeBtn, resetBtn;
	var sourceMenu, resMenu, tiltMenu, speedMenu, holdBox, targetBox, ceilBox, statusText;
	var <spectrumView, <meterView, <imagerView, <historyView, <hints;
	var busNames, font, smallFont, boldFont, monoFont;

	*initClass {
		colBg     = Color(0.13, 0.13, 0.15);
		colPanel  = Color(0.20, 0.20, 0.24);
		colText   = Color(0.86, 0.87, 0.89);
		colDim    = Color(0.55, 0.56, 0.62);
		colAccent = Color(0.98, 0.65, 0.20);
		colMeter  = Color(0.42, 0.76, 0.95);
		colClip   = Color(0.95, 0.35, 0.30);
		colOff    = Color(0.09, 0.10, 0.12);
		colGrid   = Color(0.30, 0.33, 0.40);
		colPeak   = Color(0.95, 0.96, 0.98);
		colGood   = Color(0.40, 0.86, 0.60);
	}

	*new { |insight, x, y| ^super.new.init(insight, x, y) }

	// one decimal, the sign kept, silence as the word: what every readout uses
	*fmt { |v|
		var r, neg;
		if (v.isNil) { ^"-" };
		if (v == -inf) { ^"-inf" };
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
		var bm = \BusManager.asClass;
		insight = argInsight;
		busNames = [];
		font = Font(Font.defaultSansFace, 11);
		smallFont = Font(Font.defaultSansFace, 9);
		boldFont = Font(Font.defaultSansFace, 12, true);
		monoFont = Font(Font.defaultMonoFace, 11);
		this.prBuild(x, y);
		ctrl = SimpleController(insight)
			.put(\bus, { { this.prRefreshHeader; this.prRefreshBusMenu }.defer })
			.put(\running, { { this.prRefreshHeader }.defer })
			.put(\settings, { { this.prRefreshHeader }.defer })
			.put(\reset, { { this.prRefreshViews }.defer })
			.put(\freed, { { this.close }.defer });
		// the dropdown lists BusManager's names, when there is one
		bm !? {
			bmCtrl = SimpleController(bm).put(\buses, { { this.prRefreshBusMenu }.defer });
		};
		runWasOn = insight.running;
		insight.run;
		this.prRefreshHeader;
		this.prRefreshBusMenu;
		this.prStartUpdater;
		window.front;
		^this
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	// --------------------------------------------------------------- build

	prBuild { |x, y|
		window = Window("insight · " ++ insight.name, Rect(x ? 100, y ? 100, 1040, 660));
		if (GUI.id == \qt) { window.view.palette = QPalette.dark };
		window.view.background = colBg;

		spectrumView = InsightSpectrumView(insight);
		meterView = InsightMeterView(insight);
		imagerView = InsightImagerView(insight);
		historyView = InsightHistoryView(insight);

		// the strip that says what each control and each reading measures.
		// The panels drive it from their own hintAt; the controls are given
		// static hints below, in prAttachHints.
		hints = InsightHintBar();
		[spectrumView, meterView, imagerView, historyView].do { |v| v.hints_(hints) };

		window.layout = VLayout(
			this.prHeaderRow,
			this.prSettingsRow,
			[HLayout(
				[VLayout(
					[spectrumView.view, stretch: 1],
					HLayout(
						imagerView.view,
						[historyView.view, stretch: 1]
					).spacing_(6)
				).spacing_(6), stretch: 1],
				meterView.view
			).spacing_(6), stretch: 1],
			this.prStatusRow,
			hints.view
		).margins_(8).spacing_(6);

		// moving onto the window itself - the gaps - puts the bar back to default
		hints.watch(window);
		this.prAttachHints;

		window.onClose = {
			ctrl.remove;
			bmCtrl !? (_.remove);
			updater !? (_.stop);
			// leave running as we found it
			if (runWasOn.not) { insight.stop };
			insight.prGuiClosed;
		};
	}

	// static hints for the controls along the top - what each one does
	prAttachHints {
		hints.attach(busBox, "The bus to analyse - type an index. Typing one stops following a BusManager name.");
		busMenu !? { hints.attach(busMenu,
			"Pick a bus by BusManager name - the analyzer follows it when the bus moves, e.g. after a reboot.") };
		hints.attach(chanBox, "How many channels to analyse, counted from the start of the bus.");
		hints.attach(runBtn, "Start or stop the analysis synth. It runs while the window is open, unless stopped here.");
		hints.attach(freezeBtn, "Freeze the picture without stopping the analysis - to read a value off a moment that has passed.");
		resetBtn !? { hints.attach(resetBtn,
			"Start the integrated loudness over and clear every held maximum: peaks, max loudness, over count, spectrum hold.") };
		hints.attach(sourceMenu, "What the spectrum analyses: the sum of every channel, one side, or the mid / side of the pair.");
		hints.attach(resMenu, "FFT size - larger resolves low frequencies better and responds more slowly.");
		hints.attach(tiltMenu, "A display tilt, dB per octave. At 3 dB/oct pink noise reads as a flat line.");
		hints.attach(speedMenu, "How much the spectrum is averaged over time - fast is responsive, slow is steady.");
		hints.attach(holdBox, "Keep a trace of the loudest level reached in each band since the last reset.");
		hints.attach(targetBox, "The loudness you are aiming at (LUFS), drawn across the loudness bars and history. A guide, not a limiter.");
		hints.attach(ceilBox, "The true-peak limit (dBTP) - a true peak past it counts as an over. A guide, not a limiter.");
	}

	prHeaderRow {
		var items;
		busBox = NumberBox().fixedWidth_(46).decimals_(0).clipLo_(0).clipHi_(4095)
			.toolTip_("the bus index. Typing one here stops following a name.")
			.action_({ |v| insight.bus_(v.value.asInteger); this.prRefreshHeader });
		// index 0 is "-", the way of saying this is a bare index: picking it
		// changes nothing, so it just reads back
		busMenu = \BusManager.asClass !? {
			PopUpMenu().fixedWidth_(100)
				.toolTip_("buses in the BusManager registry."
					"\nPicking one follows that name: the insight goes with it"
					"\nwhen the bus behind it moves.")
				.action_({ |v|
					var i = v.value ? 0;
					if (i > 0) { insight.bus_(busNames[i - 1]) };
					this.prRefreshBusMenu;
				})
		};
		chanBox = NumberBox().fixedWidth_(36).decimals_(0).clipLo_(1).clipHi_(64)
			.toolTip_("how many channels to analyse, from the start of the bus")
			.action_({ |v| insight.channels_(v.value.asInteger) });
		runBtn = Button().fixedWidth_(56)
			.states_([["run", colText, colPanel], ["stop", Color.black, colGood]])
			.toolTip_("the analysis synth: on while the window is open, unless stopped here")
			.action_({ if (insight.running) { insight.stop } { insight.run } });
		freezeBtn = Button().fixedWidth_(60)
			.states_([["freeze", colText, colPanel], ["frozen", Color.black, colAccent]])
			.toolTip_("hold the picture. The analysis carries on underneath.")
			.action_({ |v| frozen = v.value == 1 });

		items = [
			StaticText().string_("insight").font_(boldFont).stringColor_(colAccent).fixedWidth_(48),
			StaticText().string_(insight.name.asString).font_(font).stringColor_(colDim).fixedWidth_(80),
			this.prLabel("bus", 20), busBox
		];
		resetBtn = this.prButton("reset", 50, { insight.reset })
			.toolTip_("integrate again from now, and drop every held maximum");
		busMenu !? { items = items.add(busMenu) };
		items = items ++ [
			this.prLabel("ch", 16), chanBox,
			nil,
			runBtn, freezeBtn, resetBtn
		];
		^HLayout(*items).spacing_(4)
	}

	prSettingsRow {
		sourceMenu = PopUpMenu().fixedWidth_(60)
			.items_(Insight.sources.collect(_.asString))
			.toolTip_("what the spectrum is of: the sum of every channel, one side, mid or side")
			.action_({ |v| insight.source_(Insight.sources[v.value ? 0]) });
		resMenu = PopUpMenu().fixedWidth_(66)
			.items_(Insight.fftSizes.collect(_.asString))
			.toolTip_("the FFT size: bigger resolves the low end better and moves slower")
			.action_({ |v| insight.fftSize_(Insight.fftSizes[v.value ? 0]) });
		tiltMenu = PopUpMenu().fixedWidth_(88)
			.items_(Insight.tilts.collect { |t| t.asString ++ " dB/oct" })
			.toolTip_("dB per octave added to the drawing, so pink noise reads flat at 3")
			.action_({ |v| insight.tilt_(Insight.tilts[v.value ? 0]) });
		speedMenu = PopUpMenu().fixedWidth_(66)
			.items_(["fast", "medium", "slow"])
			.toolTip_("how much the spectrum is averaged over time")
			.action_({ |v| insight.smoothing_([0, 0.6, 0.85][v.value ? 0]) });
		holdBox = CheckBox().string_("max hold")
			.toolTip_("keep a trace of the loudest thing seen in each band since reset")
			.action_({ |v| insight.hold_(v.value) });
		targetBox = NumberBox().fixedWidth_(44).decimals_(0).clipLo_(-60).clipHi_(0)
			.toolTip_("the loudness aimed at: a line on the loudness bars and the history")
			.action_({ |v| insight.target_(v.value) });
		ceilBox = NumberBox().fixedWidth_(44).decimals_(1).clipLo_(-20).clipHi_(6)
			.toolTip_("a true peak past this is an over, and is counted")
			.action_({ |v| insight.ceiling_(v.value) });

		^HLayout(
			this.prLabel("spectrum", 48),
			this.prLabel("source", 34), sourceMenu,
			this.prLabel("res", 18), resMenu,
			this.prLabel("tilt", 18), tiltMenu,
			speedMenu, holdBox,
			nil,
			this.prLabel("target", 34), targetBox, this.prLabel("LUFS", 28),
			this.prLabel("ceiling", 38), ceilBox, this.prLabel("dBTP", 30)
		).spacing_(4)
	}

	prStatusRow {
		statusText = StaticText().font_(smallFont).stringColor_(colDim);
		^HLayout(statusText, nil, hints.toggle).spacing_(4)
	}

	// ------------------------------------------------------------- refresh

	prRefreshHeader {
		busBox.value = insight.bus ? 0;
		chanBox.value = insight.channels;
		runBtn.value = insight.running.binaryValue;
		sourceMenu.value = Insight.sources.indexOf(insight.source) ? 0;
		resMenu.value = Insight.fftSizes.indexOf(insight.fftSize) ? 0;
		tiltMenu.value = Insight.tilts.indexOf(insight.tilt) ? (Insight.tilts.indexOf(3) ? 0);
		speedMenu.value = [0, 0.6, 0.85].detectIndex { |s| s >= insight.smoothing } ? 2;
		holdBox.value = insight.hold;
		targetBox.value = insight.target;
		ceilBox.value = insight.ceiling;
		lastStatus = nil;
	}

	// which name the menu shows: the one followed if one is, otherwise the
	// first name that happens to sit on this index
	prRefreshBusMenu {
		var bm = \BusManager.asClass, pick, index;
		if (busMenu.isNil or: { bm.isNil }) { ^this };
		busNames = bm.entries.select(_.isAudio).collect(_.name);
		busMenu.items = ["-"] ++ busNames.collect(_.asString);
		index = insight.bus;
		pick = if (insight.busName.notNil and: { busNames.includes(insight.busName) }) {
			insight.busName
		} {
			busNames.detect { |n| bm.index(n) == index }
		};
		busMenu.value = pick !? { busNames.indexOf(pick) + 1 } ? 0;
	}

	prRefreshViews {
		spectrumView.refresh(true);
		meterView.refresh;
		imagerView.refresh;
		historyView.refresh;
	}

	prRefreshStatus {
		var status, parts;
		parts = ["bus " ++ (insight.bus ? "?")];
		insight.busName !? { |n| parts = parts.add(n.asString) };
		parts = parts.add(insight.channels.asString ++ " ch");
		if (insight.server.serverRunning) {
			insight.sampleRate !? { |sr| parts = parts.add((sr / 1000).round(0.1).asString ++ " kHz") };
			parts = parts.add("fft " ++ insight.fftSize);
			parts = parts.add(case
				{ insight.running.not } { "stopped" }
				{ insight.isAlive } { "running" }
				{ insight.isResolved.not } { "waiting for the bus" }
				{ true } { "starting" });
			if (insight.running) {
				parts = parts.add("integrating " ++ InsightGUI.time(insight.loudness.duration));
			};
			if (insight.overs > 0) {
				parts = parts.add(insight.overs.asString ++ if (insight.overs == 1) { " over" } { " overs" });
			};
		} {
			parts = parts.add("server not running");
		};
		status = parts.join(" · ");
		if (status != lastStatus) {
			lastStatus = status;
			statusText.string = status;
		};
	}

	prStartUpdater {
		updater = Routine({
			var tick = 0;
			loop {
				0.04.wait;
				if (window.isClosed) {
					updater.stop;
				} {
					spectrumView.refresh(frozen);
					if (frozen.not) {
						meterView.refresh;
						imagerView.refresh;
						if ((tick % 5) == 0) { historyView.refresh };
					};
					if ((tick % 12) == 0) { this.prRefreshStatus };
					tick = tick + 1;
				};
			};
		}).play(AppClock);
	}

	// -------------------------------------------------------------- widgets

	prLabel { |str, width|
		^StaticText().string_(str).font_(smallFont).stringColor_(colDim)
			.fixedWidth_(width)
	}

	prButton { |str, width, func|
		^Button().fixedWidth_(width).states_([[str, colText, colPanel]])
			.action_({ func.value })
	}
}


// =====================================================================
// InsightHintBar - a strip that says what each control and reading measures
//
// The same idea as ModuleHintBar in the module family, written here so
// Insight keeps depending on nothing: a line along the bottom of the
// window that explains whatever the pointer is on. Over a control it
// says what the control does; over a meter, a number or a panel it says
// what that value measures and how to read it.
//
//   hints = InsightHintBar();
//   hints.watch(window);                    // once, after the window exists
//   window.layout = VLayout(..., hints.view);
//
//   hints.attach(box, "what this box does");
//   view.hints_(hints);                     // a panel drives it from its hintAt(x, y)
//
// `attach` answers what it was given, so a control can be wrapped where
// it is built. A hint is a String, or a Function for one that depends on
// the moment. Moving onto nothing in particular puts the bar back to its
// default, and link::#-toggle:: folds it away.
// =====================================================================

InsightHintBar {
	classvar <>defaultHeight = 26;

	var <view, <text, <>defaultString;
	var toggleBox, font;

	*new { |defaultString, height| ^super.new.init(defaultString, height) }

	init { |str, height|
		defaultString = str ?? {
			"hover a control or a reading and this bar says what it measures"
		};
		font = Font(Font.defaultSansFace, 10);
		text = StaticText().font_(font).stringColor_(InsightGUI.colDim)
			.string_(defaultString);
		view = View().background_(InsightGUI.colOff)
			.fixedHeight_(height ? defaultHeight);
		view.layout = HLayout(text).margins_([8, 2, 8, 2]);
		^this
	}

	// A child's mouse-over only arrives if the window is willing to hear
	// about it. Moving onto the window itself - the gaps between controls -
	// puts the bar back to its default, so a stale hint does not sit there
	// describing something the pointer left a while ago.
	watch { |window|
		var v = window.tryPerform(\view) ? window;
		v !? {
			v.acceptsMouseOver = true;
			v.mouseOverAction = { this.clear };
		};
		^this
	}

	// `hint` is a String, or a Function answering one. Answers `views`, so
	// this can be wrapped around a control where it is built.
	attach { |views, hint|
		views.asArray.do { |v|
			v !? {
				v.acceptsMouseOver = true;
				v.mouseOverAction = { this.string_(hint.value) };
			};
		};
		^views
	}

	string_ { |str| text !? { text.string = str ? defaultString } }

	string { ^text !? (_.string) }

	clear { this.string_(nil) }

	visible { ^view.visible }

	visible_ { |bool|
		view.visible = bool;
		toggleBox !? { |b| b.value = bool };
	}

	// A checkbox that folds the bar away. Made on the first ask and the
	// same one after that.
	toggle {
		toggleBox ?? {
			toggleBox = CheckBox().string_("hints").value_(view.visible)
				.action_({ |v| view.visible = v.value });
			this.attach(toggleBox, "this bar - untick to fold it away");
		};
		^toggleBox
	}
}
