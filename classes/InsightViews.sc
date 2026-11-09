// =====================================================================
// InsightViews - the panes of the window, each a UserView (sc-fx-ui)
//
//   InsightSpectrumView   log frequency: now, a moment ago, the loudest held
//   InsightMeterView      a bar per channel (dBFS) and two for loudness (LUFS)
//   InsightImagerView     the goniometer, correlation and balance
//   InsightHistoryView    short term loudness over the last minute or five
//   InsightReadingsView   the numbers: integrated big, the rest under it
//
//   v = InsightSpectrumView(insight);
//   HLayout(..., v.view);
//   v.refresh;                       // from the panel's updater
//
// Each one reads the insight and draws what it finds, in FxPalette's
// colours: signal blue for levels and spectra, the same blue paler for
// loudness, white for what is held, outlines for the targets, red only
// for an over. None of them schedules anything: the panel's updater calls
// refresh. Each says what is under the pointer in the hint bar (`hints`),
// and the target, the ceiling and the spectrum's range are dragged where
// they are drawn - up and down, shift for fine, the wheel in steps, a
// double click back to the default.
// =====================================================================

InsightSpectrumView {
	var <insight, <view, <>hints;
	var hover, drawnFrame = -1, drawnHover, drawnRunning;
	var down = false, moved = false, downY = 0, downFloor = -96;
	var padL = 30, padR = 8, padT = 6, padB = 16;

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		view = UserView().minHeight_(160).minWidth_(360);
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| this.prHover(x, y) };
		view.mouseLeaveAction = { hover = nil; view.refresh };
		view.mouseDownAction = { |v, x, y, mod, button, count| this.prMouseDown(x, y, mod, count) };
		view.mouseMoveAction = { |v, x, y, mod| this.prMouseMove(x, y, mod) };
		view.mouseUpAction = { |v, x, y| this.prMouseUp(x, y) };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(mod, yd) };
		^this
	}

	refresh { |frozen = false|
		var frame = insight.spectrum !? (_.frames) ? -1;
		if (hover != drawnHover or: { insight.running != drawnRunning }
			or: { frozen.not and: { frame != drawnFrame } }) {
			view.refresh;
		};
	}

	// ------------------------------------------------------------ gestures

	// up and down moves the floor of the range; a click that does not move
	// clears the held trace
	prMouseDown { |x, y, mod, count|
		if (count == 2) { insight.spectrumFloor = -96; down = false; this.prSay(x, y); ^true };
		down = true;
		moved = false;
		downY = y;
		downFloor = insight.spectrumFloor;
		^true
	}

	prMouseMove { |x, y, mod|
		var fine = mod.isShift;
		if (down.not) { ^true };
		if ((y - downY).abs > 2) { moved = true };
		if (moved) {
			insight.spectrumFloor = (downFloor - ((y - downY) * if (fine) { 0.03 } { 0.3 })).round(1);
			hover = x @ y;
			this.prSay(x, y);
			view.refresh;
		};
		^true
	}

	prMouseUp { |x, y|
		if (down and: { moved.not }) { insight.spectrum !? (_.resetMaxima); view.refresh };
		down = false;
		^true
	}

	prWheel { |mod, yd|
		if (yd != 0) {
			insight.spectrumFloor = (insight.spectrumFloor - (yd.sign * if (mod.isShift) { 1 } { 6 }));
			view.refresh;
		};
		^true
	}

	prHover { |x, y|
		hover = x @ y;
		this.prSay(x, y);
	}

	prSay { |x, y| hints !? { hints.string_(this.hintAt(x, y)) } }

	// ------------------------------------------------------------- reading

	prGeometry {
		var w = view.bounds.width, h = view.bounds.height;
		^(l: padL, t: padT, gw: w - padL - padR, gh: h - padT - padB, w: w, h: h)
	}

	freqAt { |x|
		var g = this.prGeometry;
		^InsightSpectrum.minFreq * ((InsightSpectrum.maxFreq / InsightSpectrum.minFreq) ** ((x - g[\l]) / g[\gw]).clip(0, 1))
	}

	// what is under the pointer, and what the gestures do
	hintAt { |x, y|
		var f = this.freqAt(x), sp = insight.spectrum, txt;
		txt = "spectrum  % · % · %".format(this.class.freqText(f), FxPalette.noteName(f), FxPalette.regionName(f));
		sp !? {
			txt = txt ++ " · " ++ InsightGUI.fmt(this.prTilted(sp.levelAt(f), f)) ++ " dB";
			if (sp.hold) { txt = txt ++ " · held " ++ InsightGUI.fmt(this.prTilted(sp.maximumAt(f), f)) };
		};
		^txt ++ "   -   drag: the range (floor %), click: clear the held trace".format(insight.spectrumFloor.asInteger)
	}

	// the tilt is a slope on what was measured: a band at the floor stays there
	prTilted { |db, f| ^if (db <= (insight.spectrumFloor + 0.01)) { db } { db + (insight.tilt * log2(f / 1000)) } }

	*freqText { |f| ^if (f >= 1000) { FxPalette.sig3(f / 1000) ++ " kHz" } { f.round(1).asInteger.asString ++ " Hz" } }

	*freqLabel { |f| ^if (f >= 1000) { (f / 1000).asInteger.asString ++ "k" } { f.asInteger.asString } }

	// ---------------------------------------------------------------- draw

	prDraw { |v|
		var g = this.prGeometry, l = g[\l], t = g[\t], gw = g[\gw], gh = g[\gh], w = g[\w];
		var sp = insight.spectrum;
		var lo = insight.spectrumFloor, hi = 6;
		var minF = InsightSpectrum.minFreq, maxF = InsightSpectrum.maxFreq;
		var logSpan = log(maxF / minF);
		var xOf = { |f| l + (gw * (log(f / minF) / logSpan)) };
		var yOf = { |db| t + (gh * (1 - ((db.clip(lo, hi) - lo) / (hi - lo)))) };
		var gridFreqs = [20, 30, 40, 50, 60, 70, 80, 90, 100, 200, 300, 400, 500, 600, 700,
			800, 900, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 20000];
		var labelFreqs = [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000];
		var live = insight.running, a = if (live) { 1 } { 0.45 };
		var db, y, x, pts, f, txt, tw, step;

		drawnFrame = sp !? (_.frames) ? -1;
		drawnHover = hover;
		drawnRunning = live;
		Pen.smoothing = true;
		Pen.width = 1;

		// the grid: frequencies, then dB, labelled every twelve
		gridFreqs.do { |gf|
			x = xOf.(gf).round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (labelFreqs.includes(gf)) { 0.8 } { 0.3 });
			Pen.line(x @ t, x @ (t + gh));
			Pen.stroke;
		};
		step = if ((hi - lo) > 72) { 12 } { 6 };
		db = 0;
		while { db >= lo } {
			y = yOf.(db).round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.9 } { 0.4 });
			Pen.line(l @ y, (l + gw) @ y);
			Pen.stroke;
			if ((db % 12) == 0) {
				Pen.stringRightJustIn(db.asString, Rect(0, y - 6, l - 4, 12), FxPalette.smallFont, FxPalette.dim);
			};
			db = db - step;
		};
		labelFreqs.do { |lf|
			x = xOf.(lf);
			case
			{ lf == labelFreqs.first } { Pen.stringLeftJustIn(this.class.freqLabel(lf), Rect(x + 2, t + gh + 2, 40, 12), FxPalette.smallFont, FxPalette.dim) }
			{ lf == labelFreqs.last } { Pen.stringRightJustIn(this.class.freqLabel(lf), Rect(x - 42, t + gh + 2, 40, 12), FxPalette.smallFont, FxPalette.dim) }
			{ true } { Pen.stringCenteredIn(this.class.freqLabel(lf), Rect(x - 20, t + gh + 2, 40, 12), FxPalette.smallFont, FxPalette.dim) };
		};

		if (sp.notNil and: { sp.frames > 0 } and: { live }) {
			// now: an area under the trace, and the trace
			pts = sp.freqs.collect { |bf, i| xOf.(bf) @ yOf.(this.prTilted(sp.levels[i], bf)) };
			Pen.moveTo(pts[0].x @ (t + gh));
			pts.do { |p| Pen.lineTo(p) };
			Pen.lineTo(pts.last.x @ (t + gh));
			Pen.fillAxialGradient(0 @ t, 0 @ (t + gh),
				FxPalette.fade(FxPalette.wave, 0.38 * a), FxPalette.fade(FxPalette.wave, 0.03 * a));
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, a);
			Pen.width = 1.5;
			Pen.moveTo(pts[0]);
			pts.do { |p| Pen.lineTo(p) };
			Pen.stroke;
			// a moment ago
			Pen.width = 1;
			Pen.strokeColor = FxPalette.fade(FxPalette.peak, 0.42 * a);
			sp.freqs.do { |bf, i|
				var p = xOf.(bf) @ yOf.(this.prTilted(sp.peaks[i], bf));
				if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
			// the loudest since reset, dashed
			if (sp.hold) {
				Pen.use {
					Pen.lineDash = FloatArray[3, 3];
					Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.7 * a);
					sp.freqs.do { |bf, i|
						var p = xOf.(bf) @ yOf.(this.prTilted(sp.maxima[i], bf));
						if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
					};
					Pen.stroke;
				};
			};
		} {
			// at rest: what was held, and why nothing moves
			if (sp.notNil and: { sp.hold }) {
				Pen.use {
					Pen.lineDash = FloatArray[3, 3];
					Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.5);
					sp.freqs.do { |bf, i|
						var p = xOf.(bf) @ yOf.(this.prTilted(sp.maxima[i], bf));
						if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
					};
					Pen.stroke;
				};
			};
			txt = this.prRestText;
			tw = txt.bounds(FxPalette.font).width + 16;
			Pen.fillColor = FxPalette.fade(FxPalette.waveBg, 0.9);
			Pen.fillRect(Rect(l + ((gw - tw) / 2), t + (gh * 0.7) - 9, tw, 18));
			Pen.stringCenteredIn(txt, Rect(l, t + (gh * 0.7) - 9, gw, 18), FxPalette.font, FxPalette.dim);
		};

		// what is under the pointer: frequency, note, level
		if (hover.notNil and: { hover.x >= l } and: { hover.x <= (l + gw) }) {
			f = this.freqAt(hover.x);
			txt = this.class.freqText(f) ++ " · " ++ FxPalette.noteName(f);
			sp !? { if (sp.frames > 0) { txt = txt ++ " · " ++ InsightGUI.fmt(this.prTilted(sp.levelAt(f), f)) ++ " dB" } };
			x = hover.x.round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.45);
			Pen.line(x @ t, x @ (t + gh));
			Pen.stroke;
			tw = txt.bounds(FxPalette.smallFont).width + 12;
			x = (hover.x + 8).min(w - tw - 4);
			Pen.fillColor = FxPalette.fade(FxPalette.bg, 0.9);
			Pen.addRoundedRect(Rect(x, t + 2, tw, 15), 3, 3);
			Pen.fill;
			Pen.stringCenteredIn(txt, Rect(x, t + 2, tw, 15), FxPalette.smallFont, FxPalette.text);
		};
	}

	prRestText {
		^case
		{ insight.running.not } { "stopped - the power button runs the analysis" }
		{ insight.server.serverRunning.not } { "waiting for the server" }
		{ insight.isResolved.not } { "waiting for the bus" }
		{ true } { "listening" }
	}
}


// ---------------------------------------------------------------------
// a bar per channel - rms solid, true peak pale above it, the highest held
// as a white line - and two bars for momentary and short term loudness,
// the target across them as an outlined band. The held values sit above
// the bars as numbers; a click drops them. The ceiling and the target are
// dragged where they are drawn.
// ---------------------------------------------------------------------

InsightMeterView {
	var <insight, <view, <>hints;
	var <>floorDb = -60, <>topDb = 3;
	var grab, moved = false, downY = 0, downValue = 0;
	var geo;							// set in prDraw, read by the hints and gestures

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		view = UserView().minWidth_(200).minHeight_(180);
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hints !? { hints.string_(this.hintAt(x, y)) } };
		view.mouseDownAction = { |v, x, y, mod, button, count| this.prMouseDown(x, y, mod, count) };
		view.mouseMoveAction = { |v, x, y, mod| this.prMouseMove(x, y, mod) };
		view.mouseUpAction = { this.prMouseUp };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(x, mod, yd) };
		^this
	}

	refresh { view.refresh }

	// which side of the meter a point is on: \ceiling over the channels, \target over the loudness
	sideAt { |x| ^if (geo.notNil and: { x >= (geo[\loudX] - 8) }) { \target } { \ceiling } }

	yOf { |db| ^geo[\top] + (geo[\barsH] * (1 - ((db.clip(floorDb, topDb) - floorDb) / (topDb - floorDb)))) }

	// ------------------------------------------------------------ gestures

	prMouseDown { |x, y, mod, count|
		grab = this.sideAt(x);
		if (count == 2) {
			if (grab == \target) { insight.target = -14 } { insight.ceiling = -1 };
			grab = nil;
			hints !? { hints.string_(this.hintAt(x, y)) };
			^true
		};
		moved = false;
		downY = y;
		downValue = if (grab == \target) { insight.target } { insight.ceiling };
		^true
	}

	prMouseMove { |x, y, mod|
		var perPx, v;
		if (grab.isNil or: { geo.isNil }) { ^true };
		if ((y - downY).abs > 2) { moved = true };
		if (moved) {
			perPx = (topDb - floorDb) / geo[\barsH].max(1) * if (mod.isShift) { 0.1 } { 1 };
			v = downValue - ((y - downY) * perPx);
			if (grab == \target) { insight.target = v.round(if (mod.isShift) { 0.1 } { 1 }) } {
				insight.ceiling = v.round(0.1)
			};
			hints !? { hints.string_(this.hintAt(x, y)) };
			view.refresh;
		};
		^true
	}

	prMouseUp {
		if (grab.notNil and: { moved.not }) { insight.resetPeaks; view.refresh };
		grab = nil;
		^true
	}

	prWheel { |x, mod, yd|
		var fine = mod.isShift;
		if (yd == 0) { ^true };
		if (this.sideAt(x) == \target) {
			insight.target = (insight.target + (yd.sign * if (fine) { 0.1 } { 1 })).round(0.1)
		} {
			insight.ceiling = (insight.ceiling + (yd.sign * if (fine) { 0.1 } { 0.5 })).round(0.1)
		};
		view.refresh;
		^true
	}

	// ------------------------------------------------------------- reading

	hintAt { |x, y|
		^if (this.sideAt(x) == \target) {
			"loudness  M % · S % LUFS, target %   -   the band is the target ±% LU; drag: the target, click: drop held"
			.format(InsightGUI.fmt(insight.loudness.momentary), InsightGUI.fmt(insight.loudness.shortTerm),
				InsightGUI.fmt(insight.target), Insight.tolerance)
		} {
			"levels  held true peak % dBTP, ceiling %   -   solid rms, pale true peak; drag: the ceiling, click: drop held"
			.format(InsightGUI.fmt(insight.maxTruePeakDb), InsightGUI.fmt(insight.ceiling))
		}
	}

	// ---------------------------------------------------------------- draw

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var n = insight.channels, loud = insight.loudness, live = insight.running;
		var top = 18, bottom = 16, scaleW = 24, gap = 4, x0 = scaleW + 4;
		var barsH = h - top - bottom;
		// every bar one width, the room left over between the two groups
		var barW = ((w - x0 - 4 - 24 - (n * gap)) / (n + 2)).clip(3, 44);
		var levelRight = x0 + (n * barW) + ((n - 1) * gap);
		var loudW = barW.max(12);
		var loudX = w - 4 - (2 * loudW) - gap;
		var a = if (live) { 1 } { 0.5 };
		var y, band, zero, held;

		geo = (top: top, barsH: barsH, loudX: loudX, levelRight: levelRight);
		Pen.smoothing = true;
		Pen.width = 1;

		// the scale: dBFS for the levels and LUFS for the loudness, on one ruler
		[0, -6, -12, -18, -24, -30, -36, -42, -48, -54, -60].do { |db|
			y = this.yOf(db).round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.9 } { 0.35 });
			Pen.line((x0 - 3) @ y, (w - 4) @ y);
			Pen.stroke;
			if ([0, -6, -12, -24, -48].includes(db)) {
				Pen.stringRightJustIn(db.asString, Rect(0, y - 6, scaleW - 2, 12), FxPalette.smallFont, FxPalette.dim);
			};
		};
		zero = this.yOf(0);

		// one bar per channel
		n.do { |i|
			var x = x0 + (i * (barW + gap));
			var r = insight.rms[i] ? 0, p = insight.truePeaks[i] ? 0, mx = insight.maxTruePeaks[i] ? 0;
			var rY = this.yOf(r.max(1e-6).ampdb), pY = this.yOf(p.max(1e-6).ampdb), mxDb = mx.max(1e-6).ampdb;
			var over = mxDb > insight.ceiling;
			Pen.fillColor = FxPalette.card;
			Pen.fillRect(Rect(x, top, barW, barsH));
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.35 * a);
			Pen.fillRect(Rect(x, pY, barW, (top + barsH - pY).max(0)));
			Pen.fillColor = FxPalette.fade(FxPalette.wave, a);
			Pen.fillRect(Rect(x, rY, barW, (top + barsH - rY).max(0)));
			if (pY < zero) {
				Pen.fillColor = FxPalette.clip;
				Pen.fillRect(Rect(x, pY, barW, zero - pY));
			};
			if (mx > 1e-5) {
				Pen.fillColor = if (over) { FxPalette.clip } { FxPalette.peak };
				Pen.fillRect(Rect(x, this.yOf(mxDb) - 1, barW, 2));
			};
			// the held true peak as a number, the over light under it
			Pen.stringCenteredIn(if (mx < 1e-3) { "-inf" } { mxDb.round(1).asInteger.asString },
				Rect(x - 6, 0, barW + 12, 12), FxPalette.smallFont, if (over) { FxPalette.clip } { FxPalette.text });
			Pen.fillColor = if (over) { FxPalette.clip } { FxPalette.control };
			Pen.fillRect(Rect(x, 13, barW, 3));
			Pen.stringCenteredIn(this.prChannelLabel(i, n), Rect(x - 6, top + barsH + 2, barW + 12, 12),
				FxPalette.smallFont, FxPalette.dim);
		};

		// the ceiling, dashed across the channels
		Pen.use {
			Pen.lineDash = FloatArray[3, 3];
			y = this.yOf(insight.ceiling).round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.7);
			Pen.line((x0 - 3) @ y, (levelRight + 3) @ y);
			Pen.stroke;
		};

		// momentary and short term, the target band outlined across them
		[["M", loud.momentary, loud.maxMomentary], ["S", loud.shortTerm, loud.maxShortTerm]].do { |item, k|
			var x = loudX + (k * (loudW + gap)), val = item[1], mx = item[2];
			Pen.fillColor = FxPalette.card;
			Pen.fillRect(Rect(x, top, loudW, barsH));
			if (val > floorDb) {
				y = this.yOf(val);
				Pen.fillColor = FxPalette.fade(InsightPanel.loud, a);
				Pen.fillRect(Rect(x, y, loudW, top + barsH - y));
			};
			if (mx > floorDb) {
				Pen.fillColor = FxPalette.peak;
				Pen.fillRect(Rect(x, this.yOf(mx) - 1, loudW, 2));
			};
			Pen.stringCenteredIn(if (val > -100) { val.round(1).asInteger.asString } { "-inf" },
				Rect(x - 6, 0, loudW + 12, 12), FxPalette.smallFont, FxPalette.text);
			Pen.stringCenteredIn(item[0], Rect(x - 6, top + barsH + 2, loudW + 12, 12), FxPalette.smallFont, FxPalette.dim);
		};
		band = Rect.fromPoints(
			(loudX - 3) @ this.yOf(insight.target + Insight.tolerance).round,
			(loudX + (2 * loudW) + gap + 3) @ this.yOf(insight.target - Insight.tolerance).round);
		InsightPanel.drawTarget(band);
	}

	prChannelLabel { |i, n|
		^case
		{ n == 1 } { "mono" }
		{ n == 2 } { ["L", "R"][i] }
		{ true } { (i + 1).asString }
	}
}


// ---------------------------------------------------------------------
// the goniometer: mid up, side across, the safe diagonals drawn; the
// correlation as a bar from 0 at its right, the balance under it
// ---------------------------------------------------------------------

InsightImagerView {
	var <insight, <view, drawnFrame = -1, <>hints;
	var geo;							// set in prDraw, read by hintAt

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		view = UserView().minWidth_(180).minHeight_(150);
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hints !? { hints.string_(this.hintAt(x, y)) } };
		^this
	}

	refresh {
		if (insight.imagerFrame != drawnFrame or: { insight.imagerData.isNil }) { view.refresh };
	}

	hintAt { |x, y|
		if (geo.notNil and: { y >= geo[\balY] }) {
			^"balance  %   -   the rms of left against right; in the middle when centred".format(this.prBalanceText)
		};
		if (geo.notNil and: { x >= geo[\corrX] }) {
			^"correlation  %   -   +1 the same (folds to mono safely), 0 unrelated, under 0 (the outlined half) they cancel summed".format(this.prSigned(insight.correlation))
		};
		^"stereo  width %   -   mid up, side across: upright is mono, a diagonal panned, across out of phase, red clipped".format(this.prWidthText)
	}

	prSigned { |v| ^if (v > 0.005) { "+" ++ FxPalette.fixed(v, 2) } { FxPalette.fixed(v, 2) } }

	prWidthText { ^FxPalette.fixed(insight.width * 100, 0) ++ " %" }

	prBalanceText {
		var bal = insight.balance;
		^if (bal.abs < 0.005) { "C" } { FxPalette.fixed(bal.abs * 100, 0) ++ " % " ++ if (bal < 0) { "L" } { "R" } }
	}

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var data = insight.imagerData, live = insight.running, a = if (live) { 1 } { 0.5 };
		var balH = 18, corrW = 40;
		var size = min(w - corrW - 12, h - balH - 8).max(20);
		var cx = 4 + ((w - corrW - 8) / 2), cy = 2 + (size / 2), rad = size / 2;
		var d = rad * 0.7071;
		var corr = insight.correlation, bal = insight.balance;
		var bx, by, bh, bw, mid, y;

		geo = (corrX: w - corrW, balY: h - balH);
		drawnFrame = insight.imagerFrame;
		Pen.smoothing = true;
		Pen.width = 1;

		// the circle, the axes, the safe diagonals (hard left, hard right)
		Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.9);
		Pen.addOval(Rect(cx - rad, cy - rad, size, size));
		Pen.stroke;
		Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.5);
		Pen.line((cx - rad) @ cy, (cx + rad) @ cy);
		Pen.line(cx @ (cy - rad), cx @ (cy + rad));
		Pen.stroke;
		Pen.use {
			Pen.lineDash = FloatArray[2, 3];
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.35);
			Pen.line((cx - d) @ (cy - d), (cx + d) @ (cy + d));
			Pen.line((cx + d) @ (cy - d), (cx - d) @ (cy + d));
			Pen.stroke;
		};
		Pen.stringAtPoint("L", (cx - d - 10) @ (cy - d - 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringAtPoint("R", (cx + d + 4) @ (cy - d - 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringAtPoint("M", (cx + 3) @ (cy - rad), FxPalette.smallFont, FxPalette.dim);
		Pen.stringAtPoint("S", (cx + rad - 8) @ (cy + 1), FxPalette.smallFont, FxPalette.dim);

		case
		{ insight.channels < 2 } {
			Pen.stringCenteredIn("one channel - no image", Rect(cx - rad, cy + 8, size, 14),
				FxPalette.smallFont, FxPalette.dim);
		}
		{ data.notNil and: { data.size >= 4 } } {
			// one line through every sample of the snapshot, the radius on a square
			// root scale so the quiet end is where the eye is; clipped samples red
			var n = data.size div: 2, first = true, clipped = [];
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.5 * a);
			n.do { |k|
				var l = data[2 * k] ? 0, r = data[(2 * k) + 1] ? 0;
				var s = (l - r) * 0.7071, m = (l + r) * 0.7071;
				var len = ((s * s) + (m * m)).sqrt;
				var scaled, px, py;
				if (len > 1e-6) {
					scaled = len.sqrt.min(1) / len;
					px = cx + (s * scaled * rad);
					py = cy - (m * scaled * rad);
				} {
					px = cx;
					py = cy;
				};
				if ((l.abs >= 1) or: { r.abs >= 1 }) { clipped = clipped.add(px @ py) };
				if (first) { Pen.moveTo(px @ py); first = false } { Pen.lineTo(px @ py) };
			};
			Pen.stroke;
			Pen.fillColor = FxPalette.clip;
			clipped.do { |p| Pen.fillOval(Rect(p.x - 1.5, p.y - 1.5, 3, 3)) };
		}
		{ true } {
			Pen.stringCenteredIn(if (live) { "no signal" } { "stopped" }, Rect(cx - rad, cy + 8, size, 14),
				FxPalette.smallFont, FxPalette.dim);
		};

		// correlation: a bar from 0, up to +1, down to -1; the lower half outlined
		bx = w - corrW + 14;
		bw = 8;
		by = 12;
		bh = h - balH - 16;
		mid = by + (bh / 2);
		Pen.fillColor = FxPalette.card;
		Pen.fillRect(Rect(bx, by, bw, bh));
		Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.4);
		Pen.addRect(Rect(bx - 2.5, mid + 0.5, bw + 5, (bh / 2) + 2));
		Pen.stroke;
		Pen.fillColor = FxPalette.fade(FxPalette.wave, a);
		y = mid - (corr.clip(-1, 1) * bh / 2);
		Pen.fillRect(Rect(bx, min(y, mid), bw, (y - mid).abs));
		Pen.fillColor = FxPalette.peak;
		Pen.fillRect(Rect(bx - 3, mid - 0.5, bw + 6, 1));
		Pen.stringCenteredIn(this.prSigned(corr), Rect(w - corrW - 2, 0, corrW + 2, 12), FxPalette.smallFont, FxPalette.text);
		Pen.stringLeftJustIn("+1", Rect(bx + bw + 3, by - 2, 20, 10), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("0", Rect(bx + bw + 3, mid - 6, 20, 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("-1", Rect(bx + bw + 3, by + bh - 8, 20, 10), FxPalette.smallFont, FxPalette.dim);

		// balance: a mark between L and R, the width at the right
		by = h - balH + 6;
		bx = 16;
		bw = w - corrW - 30;
		mid = bx + (bw / 2);
		Pen.stringLeftJustIn("L", Rect(2, by - 4, 12, 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("R", Rect(bx + bw + 4, by - 4, 12, 12), FxPalette.smallFont, FxPalette.dim);
		Pen.fillColor = FxPalette.card;
		Pen.fillRect(Rect(bx, by, bw, 5));
		Pen.fillColor = FxPalette.dim;
		Pen.fillRect(Rect(mid - 0.5, by - 2, 1, 9));
		Pen.fillColor = FxPalette.fade(FxPalette.peak, a);
		Pen.fillRect(Rect(mid + (bw / 2 * bal.clip(-1, 1)) - 2, by - 2, 4, 9));
		Pen.stringLeftJustIn("width " ++ this.prWidthText, Rect(0, 0, 80, 12), FxPalette.smallFont, FxPalette.text);
	}
}


// ---------------------------------------------------------------------
// short term loudness over the last minute (or five), the target as an
// outlined band across it and the integrated value as a dotted line.
// Dragging up and down moves the target.
// ---------------------------------------------------------------------

InsightHistoryView {
	var <insight, <view, <>hints;
	var <>floorDb = -48, <>topDb = 0;
	var hover, down = false, moved = false, downY = 0, downTarget = -14;
	var padL = 30, padR = 8, padT = 6, padB = 16;

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		view = UserView().minWidth_(200).minHeight_(120);
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hover = x @ y; this.prSay(x, y); view.refresh };
		view.mouseLeaveAction = { hover = nil; view.refresh };
		view.mouseDownAction = { |v, x, y, mod, button, count| this.prMouseDown(x, y, mod, count) };
		view.mouseMoveAction = { |v, x, y, mod| this.prMouseMove(x, y, mod) };
		view.mouseUpAction = { down = false; true };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd| this.prWheel(mod, yd) };
		^this
	}

	refresh { view.refresh }

	prSay { |x, y| hints !? { hints.string_(this.hintAt(x, y)) } }

	prMouseDown { |x, y, mod, count|
		if (count == 2) { insight.target = -14; down = false; this.prSay(x, y); ^true };
		down = true;
		moved = false;
		downY = y;
		downTarget = insight.target;
		^true
	}

	prMouseMove { |x, y, mod|
		var gh = view.bounds.height - padT - padB, perPx;
		if (down.not) { ^true };
		if ((y - downY).abs > 2) { moved = true };
		if (moved) {
			perPx = (topDb - floorDb) / gh.max(1) * if (mod.isShift) { 0.1 } { 1 };
			insight.target = (downTarget - ((y - downY) * perPx)).round(if (mod.isShift) { 0.1 } { 1 });
			hover = x @ y;
			this.prSay(x, y);
			view.refresh;
		};
		^true
	}

	prWheel { |mod, yd|
		if (yd != 0) {
			insight.target = (insight.target + (yd.sign * if (mod.isShift) { 0.1 } { 1 })).round(0.1);
			view.refresh;
		};
		^true
	}

	// how far back a point is, and the loudness there
	prAt { |x|
		var hist = insight.history, gw = view.bounds.width - padL - padR;
		var maxN = this.prMaxN, i, secs;
		secs = (1 - ((x - padL) / gw).clip(0, 1)) * insight.historySeconds;
		i = hist.size - 1 - (secs * Insight.loudnessRate).round.asInteger;
		^[secs, if (i >= 0 and: { i < hist.size }) { hist[i] } { nil }]
	}

	prMaxN { ^(insight.historySeconds * Insight.loudnessRate).asInteger.max(10) }

	hintAt { |x, y|
		var at = this.prAt(x), lv = at[1], txt;
		txt = "short term  ";
		txt = txt ++ if (lv.notNil and: { lv > -100 }) {
			"-% s: % LUFS, %".format(at[0].round(1).asInteger, InsightGUI.fmt(lv), InsightPanel.deltaText(lv, insight.target))
		} { "" };
		^txt ++ "   -   dotted: integrated; drag: the target"
	}

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var hist = insight.history, maxN = this.prMaxN, live = insight.running, a = if (live) { 1 } { 0.5 };
		var gw = w - padL - padR, gh = h - padT - padB;
		var yOf = { |db| padT + (gh * (1 - ((db.clip(floorDb, topDb) - floorDb) / (topDb - floorDb)))) };
		var xOf = { |i| padL + (gw * (i + (maxN - hist.size)) / maxN) };
		var integrated = insight.loudness.integrated;
		var y, x, db, step, secs, band, tag, tw, at;

		Pen.smoothing = true;
		Pen.width = 1;

		// LUFS up, seconds along
		db = 0;
		while { db >= floorDb } {
			y = yOf.(db).round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.9 } { 0.35 });
			Pen.line(padL @ y, (padL + gw) @ y);
			Pen.stroke;
			if ((db % 12) == 0) {
				Pen.stringRightJustIn(db.asString, Rect(0, y - 6, padL - 4, 12), FxPalette.smallFont, FxPalette.dim);
			};
			db = db - 6;
		};
		secs = insight.historySeconds;
		step = if (secs > 120) { 60 } { 15 };
		(0, step .. secs).do { |s|
			x = (padL + gw - (gw * s / secs)).round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.35);
			Pen.line(x @ padT, x @ (padT + gh));
			Pen.stroke;
			tag = case { s == 0 } { "now" } { step >= 60 } { "-% min".format(s div: 60) } { true } { "-% s".format(s) };
			case
			{ s == secs } { Pen.stringLeftJustIn(tag, Rect(x + 2, padT + gh + 2, 48, 12), FxPalette.smallFont, FxPalette.dim) }
			{ s == 0 } { Pen.stringRightJustIn(tag, Rect(x - 50, padT + gh + 2, 48, 12), FxPalette.smallFont, FxPalette.dim) }
			{ true } { Pen.stringCenteredIn(tag, Rect(x - 24, padT + gh + 2, 48, 12), FxPalette.smallFont, FxPalette.dim) };
		};

		// the target, as an outlined band
		band = Rect.fromPoints(padL @ yOf.(insight.target + Insight.tolerance).round,
			(padL + gw) @ yOf.(insight.target - Insight.tolerance).round);
		InsightPanel.drawTarget(band);
		tag = "target " ++ InsightGUI.fmt(insight.target);
		tw = tag.bounds(FxPalette.smallFont).width + 8;
		Pen.fillColor = FxPalette.fade(FxPalette.waveBg, 0.85);
		Pen.fillRect(Rect(padL + gw - tw - 2, band.top - 13, tw, 12));
		Pen.stringCenteredIn(tag, Rect(padL + gw - tw - 2, band.top - 13, tw, 12), FxPalette.smallFont, FxPalette.text);

		// short term: the area under the line, then the line
		if (hist.size > 1) {
			var k = (hist.size / gw.max(1)).floor.asInteger.max(1), first = true;
			Pen.moveTo(xOf.(0) @ (padT + gh));
			hist.do { |lv, i| if ((i % k) == 0 or: { i == (hist.size - 1) }) { Pen.lineTo(xOf.(i) @ yOf.(lv.max(floorDb))) } };
			Pen.lineTo(xOf.(hist.size - 1) @ (padT + gh));
			Pen.fillColor = FxPalette.fade(InsightPanel.loud, 0.16 * a);
			Pen.fill;
			Pen.strokeColor = FxPalette.fade(InsightPanel.loud, a);
			Pen.width = 1.5;
			hist.do { |lv, i|
				if ((i % k) == 0 or: { i == (hist.size - 1) }) {
					var p = xOf.(i) @ yOf.(lv.max(floorDb));
					if (first) { Pen.moveTo(p); first = false } { Pen.lineTo(p) };
				};
			};
			Pen.stroke;
			Pen.width = 1;
		} {
			Pen.stringCenteredIn(if (live) { "the loudness draws here as it comes" } { "stopped" },
				Rect(padL, padT + (gh / 2), gw, 16), FxPalette.font, FxPalette.dim);
		};

		// the integrated loudness so far, dotted, named at its left
		if (integrated > floorDb) {
			y = yOf.(integrated).round + 0.5;
			Pen.use {
				Pen.lineDash = FloatArray[1, 3];
				Pen.strokeColor = FxPalette.peak;
				Pen.line(padL @ y, (padL + gw) @ y);
				Pen.stroke;
			};
			tag = "integrated " ++ InsightGUI.fmt(integrated);
			tw = tag.bounds(FxPalette.smallFont).width + 8;
			Pen.fillColor = FxPalette.fade(FxPalette.waveBg, 0.85);
			Pen.fillRect(Rect(padL + 2, y - 14, tw, 12));
			Pen.stringCenteredIn(tag, Rect(padL + 2, y - 14, tw, 12), FxPalette.smallFont, FxPalette.peak);
		};

		// the pointer: a line and the reading there
		if (hover.notNil and: { hover.x >= padL } and: { hover.x <= (padL + gw) }) {
			at = this.prAt(hover.x);
			x = hover.x.round + 0.5;
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.45);
			Pen.line(x @ padT, x @ (padT + gh));
			Pen.stroke;
			if (at[1].notNil and: { at[1] > -100 }) {
				tag = "% s · % LUFS".format(at[0].round(1).asInteger.neg, InsightGUI.fmt(at[1]));
				tw = tag.bounds(FxPalette.smallFont).width + 12;
				x = (hover.x + 8).min(w - tw - 4);
				Pen.fillColor = FxPalette.fade(FxPalette.bg, 0.9);
				Pen.addRoundedRect(Rect(x, padT + 2, tw, 15), 3, 3);
				Pen.fill;
				Pen.stringCenteredIn(tag, Rect(x, padT + 2, tw, 15), FxPalette.smallFont, FxPalette.text);
			};
		};
	}
}


// ---------------------------------------------------------------------
// the numbers: the integrated loudness big (outlined while it is on the
// target), how far it is from the target, then short term, range and the
// peak to loudness ratio, true peak, sample peak and overs. Each says what
// it measures in the hint bar; a click on the peaks drops them.
// ---------------------------------------------------------------------

InsightReadingsView {
	classvar <bigFont;
	var <insight, <view, <>hints;
	var rowsTop;						// set in prDraw, read by hintAt

	*initClass { bigFont = Font(Font.defaultSansFace, 30, true) }

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		view = UserView().fixedHeight_(104).minWidth_(220);
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hints !? { hints.string_(this.hintAt(x, y)) } };
		view.mouseDownAction = { |v, x, y| if (rowsTop.notNil and: { y >= rowsTop } and: { x >= (view.bounds.width / 2) }) { insight.resetPeaks }; true };
		^this
	}

	refresh { view.refresh }

	// [label, value, unit, colour, hint] for each small reading
	readings {
		var loud = insight.loudness, tp = insight.maxTruePeakDb, over = tp > insight.ceiling;
		var plr = if (loud.integrated > -100 and: { tp > -100 }) { tp - loud.integrated } { nil };
		^[
			["short", InsightGUI.fmt(loud.shortTerm), "LUFS", nil,
				"the last 3 s, K-weighted"],
			["true peak", InsightGUI.fmt(tp), "dBTP", if (over) { FxPalette.clip },
				"the highest since reset, between the samples too; red past the ceiling; click: drop it"],
			["range", InsightGUI.fmt(loud.range), "LU", nil,
				"loudness range (LRA): how far apart the quiet and the loud parts are"],
			["peak", InsightGUI.fmt(insight.maxPeakDb), "dBFS", if (insight.maxPeakDb > 0) { FxPalette.clip },
				"the highest sample since reset; click: drop it"],
			["plr", plr !? { InsightGUI.fmt(plr) } ? "-", "dB", nil,
				"peak to loudness ratio: the punch left - a squashed master reads under 8"],
			["overs", insight.overs.asString, "", if (insight.overs > 0) { FxPalette.clip },
				"how many times the true peak crossed the ceiling; click: count again"]
		]
	}

	hintAt { |x, y|
		var col, row, rd;
		if (rowsTop.isNil or: { y < rowsTop }) {
			^"integrated  % LUFS, %   -   since reset, gated (BS.1770): what a delivery asks for, outlined within ±% LU"
			.format(InsightGUI.fmt(insight.loudness.integrated),
				InsightPanel.deltaText(insight.loudness.integrated, insight.target), Insight.tolerance)
		};
		col = if (x < (view.bounds.width / 2)) { 0 } { 1 };
		row = ((y - rowsTop) / 16).floor.asInteger.clip(0, 2);
		rd = this.readings[(row * 2) + col];
		^"%  % %   -   %".format(rd[0], rd[1], rd[2], rd[4])
	}

	prDraw { |v|
		var w = v.bounds.width, loud = insight.loudness, i = loud.integrated;
		var on = (i > -100) and: { (i - insight.target).abs <= Insight.tolerance };
		var num = InsightGUI.fmt(i), nw, box, colW = w / 2;
		Pen.smoothing = true;
		Pen.stringLeftJustIn("integrated", Rect(0, 0, 120, 12), FxPalette.smallFont, FxPalette.dim);
		nw = num.bounds(bigFont).width;
		box = Rect(0, 13, nw + 12, 38);
		if (on) {
			Pen.strokeColor = FxPalette.fade(FxPalette.text, 0.75);
			Pen.width = 1;
			Pen.addRoundedRect(box.insetBy(0.5, 0.5), 4, 4);
			Pen.stroke;
		};
		Pen.stringLeftJustIn(num, Rect(6, 13, nw + 4, 38), bigFont, FxPalette.text);
		Pen.stringLeftJustIn("LUFS", Rect(box.right + 6, 31, 40, 14), FxPalette.font, FxPalette.dim);
		Pen.stringLeftJustIn(
			if (on) { "on the target" } { InsightPanel.deltaText(i, insight.target) },
			Rect(box.right + 46, 31, w - box.right - 46, 14), FxPalette.font, FxPalette.text);
		rowsTop = 56;
		this.readings.do { |rd, k|
			var x = (k % 2) * colW, y = rowsTop + ((k div: 2) * 16);
			Pen.stringLeftJustIn(rd[0], Rect(x, y, 60, 14), FxPalette.font, FxPalette.dim);
			Pen.stringRightJustIn(rd[1], Rect(x + 52, y, colW - 52 - 40, 14), FxPalette.font, rd[3] ? FxPalette.text);
			Pen.stringLeftJustIn(rd[2], Rect(x + colW - 36, y + 1, 36, 13), FxPalette.smallFont, FxPalette.dim);
		};
	}
}
