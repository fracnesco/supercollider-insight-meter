// =====================================================================
// InsightLoudness - loudness the way ITU-R BS.1770 and EBU R128 mean it
//
// The server does the part that has to run per sample: the K-weighting
// filter, and a running mean square over the last 100 ms. It hands one
// block of those mean squares - one per channel - to this, ten times a
// second, and everything else is worked out here in the language,
// because it is bookkeeping rather than signal processing:
//
//   momentary    the last 400 ms: the last four blocks
//   shortTerm    the last 3 s: the last thirty blocks
//   integrated   everything since reset, gated the way the spec says:
//                blocks below -70 LUFS are dropped, then blocks more than
//                10 LU below the mean of what is left are dropped too,
//                and what remains is averaged
//   range        LRA, EBU Tech 3342: the spread between the 10th and the
//                95th percentile of the short term values, after a -70
//                LUFS absolute and a -20 LU relative gate
//
//   l = InsightLoudness(2);
//   l.add([0.001, 0.001]);           // one block: mean square per channel
//   l.momentary;                     // -> LUFS
//   l.integrated; l.range;
//   l.maxMomentary; l.maxShortTerm;  // the highest value seen since reset
//   l.reset;
//
// The gated statistics are histograms, 0.1 LU wide from -70 to +10, so
// a session that has run for hours costs no more per block than one
// that has run for seconds. Silence is answered as -inf, which is what
// a loudness meter shows for it.
//
// A 5.1 layout weights the surrounds by +1.5 dB and leaves the LFE out,
// as the spec does. Any other channel count is weighted evenly.
// =====================================================================

InsightLoudness {
	classvar <>absoluteGate = -70;			// LUFS: below this a block is not a block
	classvar <>relativeGate = -10;			// LU under the mean, for the integrated value
	classvar <>rangeGate = -20;				// LU under the mean, for the loudness range
	classvar <binWidth = 0.1, <binFloor = -70, <binTop = 10;

	var <channels, <weights;
	var <momentary, <shortTerm, <integrated, <range;
	var <maxMomentary, <maxShortTerm;
	var <blocks = 0;						// blocks taken since reset
	var ring, ringPos;						// the last thirty blocks, oldest overwritten
	var iCount, iSum, iTotal, iPower;		// integrated: count and power per bin, and their totals
	var sCount, sSum, sTotal, sPower;		// the same for the short term values
	var numBins;

	*new { |channels = 2| ^super.new.init(channels) }

	init { |n|
		channels = (n ? 2).asInteger.max(1);
		weights = this.class.weightsFor(channels);
		numBins = ((binTop - binFloor) / binWidth).round.asInteger + 1;
		this.reset;
		^this
	}

	// L, R, C at 1, the LFE left out, the surrounds at +1.5 dB - for a 5.1
	// layout. Anything else is weighted evenly, which is what the spec does
	// for mono and stereo and the only honest answer for a bus of eight.
	*weightsFor { |n|
		^if (n == 6) { [1, 1, 1, 0, 1.41, 1.41] } { 1 ! n }
	}

	reset {
		ring = Array.fill(30, { 0.0 ! channels });
		ringPos = 0;
		blocks = 0;
		iCount = 0 ! numBins;
		iSum = 0.0 ! numBins;
		iTotal = 0;
		iPower = 0.0;
		sCount = 0 ! numBins;
		sSum = 0.0 ! numBins;
		sTotal = 0;
		sPower = 0.0;
		momentary = -inf;
		shortTerm = -inf;
		integrated = -inf;
		range = 0;
		maxMomentary = -inf;
		maxShortTerm = -inf;
		^this
	}

	// how long has been integrated, in seconds: the blocks are 100 ms
	duration { ^blocks * 0.1 }

	// one block: the mean square of the K-weighted signal, one per channel
	add { |powers|
		var mPow, sPow, m, s;
		if (powers.size < channels) { ^this };
		ring[ringPos] = powers.keep(channels);
		ringPos = (ringPos + 1) % 30;
		blocks = blocks + 1;
		mPow = this.prWeighted(this.prMean(4));
		sPow = this.prWeighted(this.prMean(30));
		m = this.class.loudnessOf(mPow);
		s = this.class.loudnessOf(sPow);
		momentary = m;
		shortTerm = s;
		if (m > maxMomentary) { maxMomentary = m };
		if (s > maxShortTerm) { maxShortTerm = s };
		if (m > absoluteGate) {
			this.prBin(iCount, iSum, m, mPow);
			iTotal = iTotal + 1;
			iPower = iPower + mPow;
			integrated = this.prIntegrated;
		};
		if (s > absoluteGate) {
			this.prBin(sCount, sSum, s, sPow);
			sTotal = sTotal + 1;
			sPower = sPower + sPow;
			range = this.prRange;
		};
		^this
	}

	// -0.691 dB is the constant in the spec: with the K filter's gain at
	// 1 kHz taken out, a 0 dBFS sine at 997 Hz reads -3.01 LUFS
	*loudnessOf { |power|
		^if (power <= 0) { -inf } { -0.691 + (10 * power.log10) }
	}

	// ----------------------------------------------------------------- private

	// the mean of the last k blocks, per channel - fewer while warming up
	prMean { |k|
		var n = k.min(blocks).max(1);
		var acc = 0.0 ! channels;
		n.do { |j|
			var block = ring.wrapAt(ringPos - 1 - j);
			channels.do { |c| acc[c] = acc[c] + block[c] };
		};
		^acc / n
	}

	prWeighted { |powers| ^(powers * weights).sum }

	prBin { |counts, sums, loudness, power|
		var k = ((loudness - binFloor) / binWidth).round.asInteger.clip(0, numBins - 1);
		counts[k] = counts[k] + 1;
		sums[k] = sums[k] + power;
	}

	prBinLoudness { |k| ^binFloor + (k * binWidth) }

	// Gate again relative to the mean of everything that passed the
	// absolute gate, then average what is left. The relative gate is
	// applied per bin, which is 0.1 LU coarse - the same shortcut every
	// meter that keeps a histogram takes.
	prIntegrated {
		var thresh, gCount = 0, gSum = 0.0;
		if (iTotal == 0) { ^-inf };
		thresh = this.class.loudnessOf(iPower / iTotal) + relativeGate;
		iCount.do { |c, k|
			if (c > 0 and: { this.prBinLoudness(k) >= thresh }) {
				gCount = gCount + c;
				gSum = gSum + iSum[k];
			};
		};
		if (gCount == 0) { ^-inf };
		^this.class.loudnessOf(gSum / gCount)
	}

	// The 10th and 95th percentiles of the gated short term values, read
	// off the cumulative count.
	prRange {
		var thresh, gated = 0, acc = 0, p10, p95;
		if (sTotal < 2) { ^0 };
		thresh = this.class.loudnessOf(sPower / sTotal) + rangeGate;
		sCount.do { |c, k|
			if (c > 0 and: { this.prBinLoudness(k) >= thresh }) { gated = gated + c };
		};
		if (gated < 2) { ^0 };
		sCount.do { |c, k|
			if (c > 0 and: { this.prBinLoudness(k) >= thresh }) {
				acc = acc + c;
				if (p10.isNil and: { acc >= (gated * 0.10) }) { p10 = this.prBinLoudness(k) };
				if (p95.isNil and: { acc >= (gated * 0.95) }) { p95 = this.prBinLoudness(k) };
			};
		};
		^((p95 ? 0) - (p10 ? 0)).max(0)
	}

	printOn { |stream|
		stream << "InsightLoudness(" << channels << "ch, M " << momentary.round(0.1)
			<< ", S " << shortTerm.round(0.1) << ", I " << integrated.round(0.1)
			<< ", LRA " << range.round(0.1) << ")"
	}
}
