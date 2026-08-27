// =====================================================================
// InsightViews - the four panels of the window, each a UserView
//
//   InsightSpectrumView   log frequency, three traces, a readout under the mouse
//   InsightMeterView      a bar per channel and two for loudness, with the numbers
//   InsightImagerView     the goniometer, correlation and balance
//   InsightHistoryView    short term loudness over the last minute
//
//   v = InsightSpectrumView(insight);
//   HLayout(..., v.view);
//   v.refresh;                       // from the window's updater
//
// Each one reads the insight and draws what it finds. None of them
// schedules anything: the window's updater calls refresh, and a view
// that has nothing new to show skips the redraw.
// =====================================================================

InsightSpectrumView {
	var <insight, <view;
	var hover, drawnFrame = -1, drawnHover, <>hints;
	var font, colFill1, colFill2, colGridFaint, colPeakLine;
	var padL = 34, padR = 10, padT = 10, padB = 20;

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		font = Font(Font.defaultSansFace, 9);
		colFill1 = InsightGUI.colMeter.copy.alpha_(0.5);
		colFill2 = InsightGUI.colMeter.copy.alpha_(0.03);
		colGridFaint = InsightGUI.colGrid.copy.alpha_(0.35);
		colPeakLine = InsightGUI.colPeak.copy.alpha_(0.6);
		view = UserView().minHeight_(220).minWidth_(400);
		view.background = InsightGUI.colOff;
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y|
			hover = x @ y;
			hints !? { hints.string_(this.hintAt(x, y)) };
		};
		view.mouseLeaveAction = { hover = nil };
		view.mouseDownAction = { insight.spectrum !? (_.resetMaxima) };
		view.mouseWheelAction = { |v, x, y, mods, xs, ys|
			if (ys != 0) { insight.spectrumFloor_(insight.spectrumFloor + (ys.sign * 6)) };
		};
		view.toolTip = "the spectrum: the loudest bin in each of 128 bands, log frequency."
			"\nblue is now, white a moment ago, orange the loudest since reset."
			"\nclick to clear the held trace, scroll to change the range.";
		^this
	}

	refresh { |frozen = false|
		var frame = insight.spectrum !? (_.frames) ? -1;
		if (hover != drawnHover or: { frozen.not and: { frame != drawnFrame } }) {
			view.refresh;
		};
	}

	// what the spectrum measures, and how to read its three traces
	hintAt { |x, y|
		^"Spectrum — the loudest FFT bin in each of 128 log-spaced frequency bands. "
		"Blue is now, white a moment ago, orange the loudest reached in each band since reset. "
		"Hover shows the frequency, nearest note and level; click clears the held trace, scroll moves the floor."
	}

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var sp = insight.spectrum;
		var gw = w - padL - padR, gh = h - padT - padB;
		var lo = insight.spectrumFloor, hi = 6;
		var tilt = insight.tilt;
		var minF = InsightSpectrum.minFreq, maxF = InsightSpectrum.maxFreq;
		var logSpan = log(maxF / minF);
		var xOf = { |f| padL + (gw * (log(f / minF) / logSpan)) };
		var yOf = { |db| padT + (gh * (1 - ((db.clip(lo, hi) - lo) / (hi - lo)))) };
		var tilted = { |db, f| db + (tilt * log2(f / 1000)) };
		var gridFreqs = [20, 30, 40, 50, 60, 70, 80, 90, 100, 200, 300, 400, 500, 600, 700,
			800, 900, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 20000];
		var labelFreqs = [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000];
		var db, y, x, pts, f, lv, mx, txt, tw;

		drawnFrame = sp !? (_.frames) ? -1;
		drawnHover = hover;
		Pen.smoothing = true;
		Pen.width = 1;

		// the grid: frequencies, then dB every twelve
		gridFreqs.do { |gf|
			x = xOf.(gf).round + 0.5;
			Pen.strokeColor = if (labelFreqs.includes(gf)) { InsightGUI.colGrid } { colGridFaint };
			Pen.line(x @ padT, x @ (padT + gh));
			Pen.stroke;
		};
		db = 0;
		while { db >= lo } {
			y = yOf.(db).round + 0.5;
			Pen.strokeColor = if (db == 0) { InsightGUI.colGrid } { colGridFaint };
			Pen.line(padL @ y, (padL + gw) @ y);
			Pen.stroke;
			Pen.stringRightJustIn(db.asString, Rect(0, y - 7, padL - 5, 14), font, InsightGUI.colDim);
			db = db - 12;
		};
		labelFreqs.do { |lf|
			x = xOf.(lf);
			Pen.stringCenteredIn(this.prFreqLabel(lf), Rect(x - 20, padT + gh + 3, 40, 14),
				font, InsightGUI.colDim);
		};

		if (sp.notNil) {
			// now: a filled area under the trace, and the trace itself
			pts = sp.freqs.collect { |bf, i| xOf.(bf) @ yOf.(tilted.(sp.levels[i], bf)) };
			Pen.moveTo(pts[0].x @ (padT + gh));
			pts.do { |p| Pen.lineTo(p) };
			Pen.lineTo(pts.last.x @ (padT + gh));
			Pen.fillAxialGradient(0 @ padT, 0 @ (padT + gh), colFill1, colFill2);
			Pen.strokeColor = InsightGUI.colMeter;
			Pen.width = 1.5;
			Pen.moveTo(pts[0]);
			pts.do { |p| Pen.lineTo(p) };
			Pen.stroke;
			// a moment ago
			Pen.strokeColor = colPeakLine;
			Pen.width = 1;
			sp.freqs.do { |bf, i|
				var p = xOf.(bf) @ yOf.(tilted.(sp.peaks[i], bf));
				if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
			};
			Pen.stroke;
			// the loudest since reset
			if (sp.hold) {
				Pen.strokeColor = InsightGUI.colAccent;
				sp.freqs.do { |bf, i|
					var p = xOf.(bf) @ yOf.(tilted.(sp.maxima[i], bf));
					if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
				};
				Pen.stroke;
			};
		};

		// what is under the mouse: frequency, note, level, held maximum
		if (hover.notNil and: { hover.x >= padL } and: { hover.x <= (padL + gw) }) {
			f = minF * ((maxF / minF) ** ((hover.x - padL) / gw));
			txt = this.prFreq(f) ++ " · " ++ this.prNote(f);
			sp !? {
				lv = tilted.(sp.levelAt(f), f);
				mx = tilted.(sp.maximumAt(f), f);
				txt = txt ++ " · " ++ InsightGUI.fmt(lv) ++ " dB";
				if (sp.hold) { txt = txt ++ " · max " ++ InsightGUI.fmt(mx) };
			};
			x = hover.x.round + 0.5;
			Pen.strokeColor = InsightGUI.colText.copy.alpha_(0.5);
			Pen.line(x @ padT, x @ (padT + gh));
			Pen.stroke;
			tw = (txt.size * 6) + 12;
			x = (hover.x + 10).min(w - tw - 4);
			Pen.fillColor = InsightGUI.colBg.copy.alpha_(0.85);
			Pen.fillRect(Rect(x, padT + 4, tw, 16));
			Pen.stringLeftJustIn(txt, Rect(x + 6, padT + 4, tw, 16), font, InsightGUI.colText);
		};
	}

	prFreqLabel { |f|
		^if (f >= 1000) { (f / 1000).asInteger.asString ++ "k" } { f.asInteger.asString }
	}

	prFreq { |f|
		^if (f >= 1000) {
			(f / 1000).round(0.01).asString ++ " kHz"
		} {
			f.round(1).asInteger.asString ++ " Hz"
		}
	}

	prNote { |f|
		var names = #["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
		var midi = f.cpsmidi;
		var n = midi.round.asInteger;
		var cents = ((midi - n) * 100).round.asInteger;
		^names[n % 12] ++ ((n div: 12) - 1) ++ (if (cents >= 0) { " +" } { " " }) ++ cents ++ "c"
	}
}


// ---------------------------------------------------------------------
// a bar per channel - rms body, true peak line, the highest held - two
// bars for momentary and short term loudness, and the numbers under them
// ---------------------------------------------------------------------

InsightMeterView {
	var <insight, <view, <>hints;
	var <>floorDb = -60, <>topDb = 3;
	var font, monoFont, colTrack, colGridFaint, colClipFaint, colAccentFaint;
	var geoBarsBottom, geoRy, geoLoudX;			// set in prDraw, read by hintAt

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		font = Font(Font.defaultSansFace, 9);
		monoFont = Font(Font.defaultMonoFace, 11);
		colTrack = InsightGUI.colBg;
		colGridFaint = InsightGUI.colGrid.copy.alpha_(0.35);
		colClipFaint = InsightGUI.colClip.copy.alpha_(0.7);
		colAccentFaint = InsightGUI.colAccent.copy.alpha_(0.7);
		view = UserView().fixedWidth_(250).minHeight_(320);
		view.background = InsightGUI.colOff;
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hints !? { hints.string_(this.hintAt(x, y)) } };
		view.toolTip = "levels: the bar is rms, the white line true peak, the orange mark"
			"\nthe highest true peak since reset - red once it is past the ceiling."
			"\nM and S are momentary and short term loudness, with the target across them."
			"\nthe numbers hold their highest values until reset.";
		^this
	}

	refresh { view.refresh }

	// each reading, by where the mouse is: the bars up top, then one row
	// per number - so hovering "I" or "TP" says exactly what it measures
	hintAt { |x, y|
		var row;
		if (geoBarsBottom.isNil) {
			^"Levels and loudness — hover a bar or a number for what it measures."
		};
		if (y < geoBarsBottom) {
			^if (geoLoudX.notNil and: { x >= (geoLoudX - 6) }) {
				"Loudness bars — M (momentary, last 400 ms) and S (short-term, last 3 s), "
				"K-weighted per BS.1770, in LUFS. The orange mark is the loudest since reset, "
				"the dashed line your target."
			} {
				"Level meters, one per channel — the filled bar is RMS (~300 ms), the white line "
				"the true peak of this interval, the orange mark the highest true peak since reset. "
				"Red once it is past the ceiling."
			};
		};
		row = ((y - geoRy + 9) / 18).floor.asInteger.clip(0, 5);
		^[
			"M · Momentary loudness (LUFS) — the mean loudness of the last 400 ms, K-weighted "
			"(BS.1770). Reads instantaneous programme level. 'max' is the loudest 400 ms since reset.",

			"S · Short-term loudness (LUFS) — the last 3 seconds, K-weighted. The window many "
			"delivery and normalisation specs watch. 'max' is the loudest 3 s since reset.",

			"I · Integrated loudness (LUFS) — every 100 ms block since reset, gated (blocks under "
			"-70 LUFS dropped, then blocks more than 10 LU below the running mean dropped) and "
			"averaged. The one programme-loudness figure: aim -14 for streaming, -23 for EBU R128 "
			"broadcast. LRA beside it is the loudness range in LU (10th-95th percentile of short-term).",

			"TP · True peak (dBTP) — the sample peak reconstructed at 4x oversampling, so "
			"inter-sample peaks a normal peak meter misses are caught. Keep it under the ceiling "
			"(-1 dBTP) to survive MP3/AAC encoding. The figure is the max held; 'now' is live.",

			"peak · Sample peak (dBFS) — the highest actual sample value since reset. "
			"'overs' counts how many times the true peak crossed the ceiling.",

			"corr · Interchannel correlation — +1 the channels are identical (mono-safe), 0 "
			"unrelated, -1 inverted (cancels when summed to mono). width — 0 mono, 1 fully spread, "
			"above 1 out of phase."
		][row];
	}

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var n = insight.channels;
		var loud = insight.loudness;
		var top = 10, bottom = 8, readH = 118, labelH = 16;
		var barsTop = top, barsH = h - top - bottom - readH - labelH;
		var scaleW = 26, gap = 4, lufsW = 16;
		var x0 = scaleW + 6;
		var right = w - 8;
		var loudX = right - (2 * lufsW) - gap;
		var levelRight = loudX - 16;
		var barW = (((levelRight - x0) - ((n - 1) * gap)) / n).clip(3, 26);
		var yOf = { |db| barsTop + (barsH * (1 - ((db.clip(floorDb, topDb) - floorDb) / (topDb - floorDb)))) };
		var y, ry, over, mxDb;

		// remembered for hintAt: the bottom of the bar area and where the numbers start
		geoBarsBottom = barsTop + barsH;
		geoLoudX = loudX;

		Pen.smoothing = true;
		Pen.width = 1;

		// the scale, shared: dBFS for the levels, LUFS for the loudness
		[3, 0, -6, -12, -18, -24, -30, -36, -42, -48, -54, -60].do { |db|
			y = yOf.(db).round + 0.5;
			Pen.strokeColor = if (db == 0) { InsightGUI.colGrid } { colGridFaint };
			Pen.line((x0 - 3) @ y, right @ y);
			Pen.stroke;
			if ([0, -12, -24, -36, -48, -60].includes(db)) {
				Pen.stringRightJustIn(db.asString, Rect(0, y - 7, scaleW, 14), font, InsightGUI.colDim);
			};
		};

		// one bar per channel
		n.do { |i|
			var x = x0 + (i * (barW + gap));
			var r = insight.rms[i] ? 0, p = insight.truePeaks[i] ? 0, mx = insight.maxTruePeaks[i] ? 0;
			var rDb = r.ampdb, pDb = p.ampdb;
			var overNow = pDb > insight.ceiling;
			mxDb = mx.ampdb;
			over = mxDb > insight.ceiling;
			Pen.fillColor = colTrack;
			Pen.fillRect(Rect(x, barsTop, barW, barsH));
			Pen.fillColor = if (overNow) { InsightGUI.colClip } { InsightGUI.colMeter };
			y = yOf.(rDb);
			Pen.fillRect(Rect(x, y, barW, barsTop + barsH - y));
			Pen.fillColor = if (overNow) { InsightGUI.colClip } { InsightGUI.colPeak };
			Pen.fillRect(Rect(x, yOf.(pDb) - 1, barW, 2));
			if (mx > 0) {
				Pen.fillColor = if (over) { InsightGUI.colClip } { InsightGUI.colAccent };
				Pen.fillRect(Rect(x, yOf.(mxDb) - 1, barW, 2));
			};
			Pen.stringCenteredIn(this.prChannelLabel(i, n), Rect(x - 4, barsTop + barsH + 1, barW + 8, 14),
				font, InsightGUI.colDim);
		};

		// the ceiling, across the level bars
		Pen.use {
			Pen.lineDash = FloatArray[3, 3];
			y = yOf.(insight.ceiling).round + 0.5;
			Pen.strokeColor = colClipFaint;
			Pen.line(x0 @ y, levelRight @ y);
			Pen.stroke;
			// and the target, across the loudness bars
			y = yOf.(insight.target).round + 0.5;
			Pen.strokeColor = colAccentFaint;
			Pen.line((loudX - 4) @ y, right @ y);
			Pen.stroke;
		};

		// momentary and short term
		[["M", loud.momentary, loud.maxMomentary], ["S", loud.shortTerm, loud.maxShortTerm]].do { |item, k|
			var x = loudX + (k * (lufsW + gap));
			var val = item[1], mx = item[2];
			Pen.fillColor = colTrack;
			Pen.fillRect(Rect(x, barsTop, lufsW, barsH));
			if (val > floorDb) {
				y = yOf.(val);
				Pen.fillColor = InsightGUI.colGood;
				Pen.fillRect(Rect(x, y, lufsW, barsTop + barsH - y));
			};
			if (mx > floorDb) {
				Pen.fillColor = InsightGUI.colAccent;
				Pen.fillRect(Rect(x, yOf.(mx) - 1, lufsW, 2));
			};
			Pen.stringCenteredIn(item[0], Rect(x, barsTop + barsH + 1, lufsW, 14), font, InsightGUI.colDim);
		};

		// the numbers
		ry = barsTop + barsH + labelH + 6;
		geoRy = ry;
		over = insight.maxTruePeakDb > insight.ceiling;
		this.prRow(ry, "M", InsightGUI.fmt(loud.momentary), "LUFS", "max", InsightGUI.fmt(loud.maxMomentary));
		this.prRow(ry + 18, "S", InsightGUI.fmt(loud.shortTerm), "LUFS", "max", InsightGUI.fmt(loud.maxShortTerm));
		this.prRow(ry + 36, "I", InsightGUI.fmt(loud.integrated), "LUFS", "LRA", InsightGUI.fmt(loud.range) ++ " LU");
		this.prRow(ry + 54, "TP", InsightGUI.fmt(insight.maxTruePeakDb), "dBTP", "now",
			InsightGUI.fmt((insight.truePeaks.maxItem ? 0).ampdb),
			if (over) { InsightGUI.colClip } { InsightGUI.colText });
		this.prRow(ry + 72, "peak", InsightGUI.fmt(insight.maxPeakDb), "dBFS", "overs",
			insight.overs.asString, if (insight.overs > 0) { InsightGUI.colClip } { InsightGUI.colText });
		this.prRow(ry + 90, "corr", InsightGUI.fmt(insight.correlation), "", "width",
			InsightGUI.fmt(insight.width));
	}

	// label, value, unit | second label, second value
	prRow { |y, label, value, unit, label2, value2, color|
		var col = color ? InsightGUI.colText;
		Pen.stringLeftJustIn(label, Rect(8, y, 40, 14), monoFont, InsightGUI.colDim);
		Pen.stringRightJustIn(value, Rect(40, y, 52, 14), monoFont, col);
		Pen.stringLeftJustIn(unit, Rect(96, y, 36, 14), font, InsightGUI.colDim);
		Pen.stringLeftJustIn(label2, Rect(140, y, 40, 14), monoFont, InsightGUI.colDim);
		Pen.stringRightJustIn(value2, Rect(180, y, 62, 14), monoFont, col);
	}

	prChannelLabel { |i, n|
		^case
		{ n == 1 } { "M" }
		{ n == 2 } { ["L", "R"][i] }
		{ true } { (i + 1).asString }
	}
}


// ---------------------------------------------------------------------
// the goniometer: mid up, side across, with correlation and balance
// ---------------------------------------------------------------------

InsightImagerView {
	var <insight, <view, drawnFrame = -1, <>hints;
	var geoCorrY, geoBalY;						// set in prDraw, read by hintAt
	var font, monoFont, colTrace, colGridFaint;

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		font = Font(Font.defaultSansFace, 9);
		monoFont = Font(Font.defaultMonoFace, 10);
		colTrace = InsightGUI.colMeter.copy.alpha_(0.45);
		colGridFaint = InsightGUI.colGrid.copy.alpha_(0.5);
		view = UserView().fixedWidth_(250).fixedHeight_(250);
		view.background = InsightGUI.colOff;
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hints !? { hints.string_(this.hintAt(x, y)) } };
		view.toolTip = "the stereo image: mid is up, side is across, so a mono signal is a"
			"\nvertical line and an out of phase one a horizontal line."
			"\ncorrelation runs from -1 (out of phase) to +1 (mono); balance from L to R.";
		^this
	}

	refresh {
		if (insight.imagerFrame != drawnFrame or: { insight.imagerData.isNil }) { view.refresh };
	}

	// the scope itself, or the two bars under it
	hintAt { |x, y|
		if (geoBalY.notNil and: { y >= (geoBalY - 4) }) {
			^"Balance — the RMS difference between left and right. C is centred; otherwise the "
			"signal leans left or right by the amount shown."
		};
		if (geoCorrY.notNil and: { y >= (geoCorrY - 4) }) {
			^"Correlation — how alike the two channels are: +1 identical (safe to fold to mono), "
			"0 unrelated, below 0 (red) they partly cancel when summed. Watch it before summing a mix."
		};
		^"Goniometer — each point is one stereo sample: mid (L+R) up, side (L-R) across. "
		"A vertical line is mono, a 45-degree lean is panned, a horizontal spread means out of phase."
	}

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var data = insight.imagerData;
		var barsH = 48;
		var size = min(w - 24, h - barsH - 24);
		var cx = w / 2, cy = 14 + (size / 2), rad = size / 2;
		var d = rad * 0.7071;
		var corr = insight.correlation, bal = insight.balance;
		var by, bx, bw, mx, colCorr;

		// remembered for hintAt: where the correlation and balance bars sit
		geoCorrY = h - barsH + 6;
		geoBalY = geoCorrY + 20;

		drawnFrame = insight.imagerFrame;
		Pen.smoothing = true;
		Pen.width = 1;

		// the circle, the axes, the diagonals
		Pen.strokeColor = InsightGUI.colGrid;
		Pen.addOval(Rect(cx - rad, cy - rad, size, size));
		Pen.stroke;
		Pen.line((cx - rad) @ cy, (cx + rad) @ cy);
		Pen.line(cx @ (cy - rad), cx @ (cy + rad));
		Pen.stroke;
		Pen.strokeColor = colGridFaint;
		Pen.line((cx - d) @ (cy - d), (cx + d) @ (cy + d));
		Pen.line((cx + d) @ (cy - d), (cx - d) @ (cy + d));
		Pen.stroke;
		Pen.stringAtPoint("L", (cx - d - 12) @ (cy - d - 12), font, InsightGUI.colDim);
		Pen.stringAtPoint("R", (cx + d + 4) @ (cy - d - 12), font, InsightGUI.colDim);
		Pen.stringAtPoint("M", (cx + 4) @ (cy - rad - 2), font, InsightGUI.colDim);
		Pen.stringAtPoint("S", (cx + rad + 2) @ (cy - 12), font, InsightGUI.colDim);

		// the trace: one line through every sample of the snapshot, radius
		// on a square root scale so the quiet end is where the eye is
		if (data.notNil and: { data.size >= 4 }) {
			var n = data.size div: 2, first = true;
			Pen.strokeColor = colTrace;
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
				if (first) { Pen.moveTo(px @ py); first = false } { Pen.lineTo(px @ py) };
			};
			Pen.stroke;
		};

		// correlation: from the middle out, red below zero
		by = h - barsH + 6;
		bx = 40;
		bw = w - 40 - 44;
		mx = bx + (bw / 2);
		colCorr = case
			{ corr < 0 } { InsightGUI.colClip }
			{ corr > 0.5 } { InsightGUI.colGood }
			{ true } { InsightGUI.colMeter };
		Pen.stringLeftJustIn("corr", Rect(6, by - 3, 34, 14), font, InsightGUI.colDim);
		Pen.fillColor = InsightGUI.colBg;
		Pen.fillRect(Rect(bx, by, bw, 8));
		Pen.fillColor = colCorr;
		if (corr >= 0) {
			Pen.fillRect(Rect(mx, by, bw / 2 * corr, 8));
		} {
			Pen.fillRect(Rect(mx + (bw / 2 * corr), by, bw / 2 * corr.neg, 8));
		};
		Pen.fillColor = InsightGUI.colPeak;
		Pen.fillRect(Rect(mx - 0.5, by - 2, 1, 12));
		Pen.stringRightJustIn(InsightGUI.fmt(corr), Rect(w - 42, by - 3, 36, 14), monoFont, InsightGUI.colText);

		// balance: a mark between L and R
		by = by + 20;
		Pen.stringLeftJustIn("bal", Rect(6, by - 3, 34, 14), font, InsightGUI.colDim);
		Pen.fillColor = InsightGUI.colBg;
		Pen.fillRect(Rect(bx, by, bw, 8));
		Pen.fillColor = InsightGUI.colPeak;
		Pen.fillRect(Rect(mx - 0.5, by - 2, 1, 12));
		Pen.fillColor = InsightGUI.colAccent;
		Pen.fillRect(Rect(mx + (bw / 2 * bal.clip(-1, 1)) - 2, by - 1, 4, 10));
		Pen.stringRightJustIn(
			if (bal.abs < 0.005) { "C" } { InsightGUI.fmt(bal.abs) ++ if (bal < 0) { "L" } { "R" } },
			Rect(w - 42, by - 3, 36, 14), monoFont, InsightGUI.colText);
	}
}


// ---------------------------------------------------------------------
// short term loudness over the last minute, with the target and the
// integrated value drawn across it
// ---------------------------------------------------------------------

InsightHistoryView {
	var <insight, <view, <>hints;
	var <>floorDb = -60, <>topDb = 0;
	var font, monoFont, colGridFaint, colFill;
	var padL = 30, padR = 10, padT = 20, padB = 10;

	*new { |insight| ^super.new.init(insight) }

	init { |argInsight|
		insight = argInsight;
		font = Font(Font.defaultSansFace, 9);
		monoFont = Font(Font.defaultMonoFace, 10);
		colGridFaint = InsightGUI.colGrid.copy.alpha_(0.35);
		colFill = InsightGUI.colGood.copy.alpha_(0.18);
		view = UserView().fixedHeight_(250).minWidth_(200);
		view.background = InsightGUI.colOff;
		view.drawFunc = { |v| this.prDraw(v) };
		view.acceptsMouseOver = true;
		view.mouseOverAction = { |v, x, y| hints !? { hints.string_(this.hintAt(x, y)) } };
		view.toolTip = "short term loudness over the last minute, newest at the right."
			"\nthe orange line is the target, the white one the integrated value.";
		^this
	}

	refresh { view.refresh }

	hintAt { |x, y|
		^"Short-term loudness over the last minute (LUFS), newest at the right. The orange line "
		"is your target, the white line the integrated loudness so far — a steady mix sits near the target."
	}

	prDraw { |v|
		var w = v.bounds.width, h = v.bounds.height;
		var hist = insight.history;
		var maxN = (insight.historySeconds * Insight.loudnessRate).asInteger.max(10);
		var gw = w - padL - padR, gh = h - padT - padB;
		var yOf = { |db| padT + (gh * (1 - ((db.clip(floorDb, topDb) - floorDb) / (topDb - floorDb)))) };
		var xOf = { |i| padL + (gw * (i + (maxN - hist.size)) / maxN) };
		var integrated = insight.loudness.integrated;
		var y, db, first = true;

		Pen.smoothing = true;
		Pen.width = 1;
		Pen.stringAtPoint("short term loudness · last % s".format(insight.historySeconds),
			padL @ 3, font, InsightGUI.colDim);

		db = 0;
		while { db >= floorDb } {
			y = yOf.(db).round + 0.5;
			Pen.strokeColor = if (db == 0) { InsightGUI.colGrid } { colGridFaint };
			Pen.line(padL @ y, (padL + gw) @ y);
			Pen.stroke;
			Pen.stringRightJustIn(db.asString, Rect(0, y - 7, padL - 4, 14), font, InsightGUI.colDim);
			db = db - 12;
		};

		if (hist.size > 1) {
			// the area under the line, then the line
			Pen.moveTo(xOf.(0) @ (padT + gh));
			hist.do { |lv, i| Pen.lineTo(xOf.(i) @ yOf.(lv.max(floorDb))) };
			Pen.lineTo(xOf.(hist.size - 1) @ (padT + gh));
			Pen.fillColor = colFill;
			Pen.fill;
			Pen.strokeColor = InsightGUI.colGood;
			hist.do { |lv, i|
				var p = xOf.(i) @ yOf.(lv.max(floorDb));
				if (first) { Pen.moveTo(p); first = false } { Pen.lineTo(p) };
			};
			Pen.stroke;
		};

		Pen.use {
			Pen.lineDash = FloatArray[3, 3];
			y = yOf.(insight.target).round + 0.5;
			Pen.strokeColor = InsightGUI.colAccent;
			Pen.line(padL @ y, (padL + gw) @ y);
			Pen.stroke;
			if (integrated > floorDb) {
				y = yOf.(integrated).round + 0.5;
				Pen.strokeColor = InsightGUI.colPeak;
				Pen.line(padL @ y, (padL + gw) @ y);
				Pen.stroke;
				Pen.stringRightJustIn("I " ++ InsightGUI.fmt(integrated), Rect(w - 80, y - 14, 70, 12),
					monoFont, InsightGUI.colPeak);
			};
		};
	}
}
