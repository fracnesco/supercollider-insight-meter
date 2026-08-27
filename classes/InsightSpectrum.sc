// =====================================================================
// InsightSpectrum - one FFT frame at a time, folded into log spaced bands
//
// The synth does one FFT and nothing else: it fills a buffer and never
// reads it back. The language fetches that buffer a frame at a time and
// takes the loudest bin in each of its bands, which is the whole reason
// for reading it here rather than reducing it on the server: a band near
// the top of the range covers a hundred bins, and a tone in it is in one
// of them. Sampling a few would miss it.
//
//   sp = InsightSpectrum(4096, 48000);
//   sp.take(frame, 0.066);           // a frame from b_getn, and the seconds since the last
//   sp.levels;                       // dB per band, falling back at fallDb a second
//   sp.peaks;                        // the same, falling slower
//   sp.maxima;                       // the loudest thing seen since resetMaxima
//   sp.freqs;                        // where each band sits
//
// Three traces, so the picture says three things at once: what is there
// now, what was there a moment ago, and the loudest thing there has
// been since the last reset. `smoothing` averages the power before any
// of that, for a display that moves like a slow analyser rather than a
// fast one.
//
// A full scale sine through the Hann window peaks at N/4 in the bin it
// lands in, so that is where 0 dB is.
// =====================================================================

InsightSpectrum {
	classvar <>numBands = 128;
	classvar <>minFreq = 20, <>maxFreq = 20000;
	classvar <>fallDb = 36, <>peakFallDb = 6;		// per second

	var <fftSize, <sampleRate;
	var <freqs, <edges, <fetchCount;
	var <levels, <peaks, <maxima;
	var <>floorDb = -96, <>topDb = 6;
	var <>smoothing = 0;					// 0 none .. 0.95 very slow
	var <>hold = true;						// keep the maxima
	var <frames = 0;						// frames taken since reset
	var avg;

	*new { |fftSize = 4096, sampleRate = 48000| ^super.new.init(fftSize, sampleRate) }

	init { |size, sr|
		fftSize = size.asInteger;
		sampleRate = sr.asFloat.clip(8000, 192000);
		freqs = Array.fill(numBands, { |i|
			minFreq * ((maxFreq / minFreq) ** (i / (numBands - 1).max(1)))
		});
		edges = this.prBandBins;
		// everything up to the top of the drawn range, and not the rest
		fetchCount = ((edges.last[1] + 1) * 2).clip(4, fftSize);
		this.reset;
		^this
	}

	// Which bins each band covers: [first, last], never empty and never past
	// the end. Bin k of the frame is the pair at 2k and 2k + 1; the buffer
	// holds dc at 0 and nyquist at 1.
	prBandBins {
		var ratio = (maxFreq / minFreq) ** (1 / (numBands - 1).max(1));
		var edge = ratio.sqrt;
		var top = ((fftSize * 0.5) - 1).asInteger;
		var scale = fftSize / sampleRate;
		^freqs.collect { |mid|
			var lo = ((mid / edge) * scale).floor.asInteger.clip(1, top);
			var hi = ((mid * edge) * scale).ceil.asInteger.clip(lo, top);
			[lo, hi]
		}
	}

	normalise { ^4 / fftSize }

	reset {
		levels = floorDb ! numBands;
		peaks = floorDb ! numBands;
		maxima = floorDb ! numBands;
		avg = 0.0 ! numBands;
		frames = 0;
		^this
	}

	// the moving traces, keeping the maxima: what a stop shows
	clear {
		levels = floorDb ! numBands;
		peaks = floorDb ! numBands;
		avg = 0.0 ! numBands;
		^this
	}

	resetMaxima {
		maxima = floorDb ! numBands;
		^this
	}

	// One frame - [dc, nyquist, re, im, re, im, ...] - and how long since
	// the last. The falls are per second and the frames are timed, so the
	// traces look the same however fast they arrive.
	take { |frame, dt = 0.066|
		var norm = this.normalise;
		var step = dt.clip(0.01, 0.5);
		var fall = fallDb * step;
		var peakFall = peakFallDb * step;
		var lo = floorDb;
		var sm = smoothing.clip(0, 0.99);
		edges.do { |pair, i|
			var best = 0.0, p, db;
			(pair[0] * 2).forBy((pair[1] * 2) + 1, 2) { |j|
				var re = frame[j] ? 0, im = frame[j + 1] ? 0;
				p = (re * re) + (im * im);
				if (p > best) { best = p };
			};
			best = best * norm * norm;
			if (sm > 0) {
				avg[i] = (avg[i] * sm) + (best * (1 - sm));
				best = avg[i];
			};
			db = if (best > 0) { (10 * best.log10).max(lo) } { lo };
			levels[i] = db.max(levels[i] - fall);
			peaks[i] = levels[i].max(peaks[i] - peakFall);
			if (hold and: { db > maxima[i] }) { maxima[i] = db };
		};
		frames = frames + 1;
		^this
	}

	// --------------------------------------------------------------- reading

	// 0..1 across the bands to a frequency, and back - what a hover readout
	// and a grid line want
	freqAt { |pos| ^minFreq * ((maxFreq / minFreq) ** pos.clip(0, 1)) }

	posOf { |freq| ^(log(freq / minFreq) / log(maxFreq / minFreq)).clip(0, 1) }

	// the trace at a frequency: the band it falls in, since a tone sits in
	// one band and reading between two would understate it
	bandAt { |freq| ^(this.posOf(freq) * (numBands - 1)).round.asInteger.clip(0, numBands - 1) }

	levelAt { |freq| ^levels[this.bandAt(freq)] }

	maximumAt { |freq| ^maxima[this.bandAt(freq)] }

	printOn { |stream|
		stream << "InsightSpectrum(" << fftSize << ", " << sampleRate.asInteger
			<< " Hz, " << numBands << " bands)"
	}
}
