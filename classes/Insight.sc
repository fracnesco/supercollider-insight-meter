// =====================================================================
// Insight - a sound engineer's window on a bus
//
// Point it at a bus - by index, by BusManager name, by Bus, or at a
// module - and it shows what a mastering chain would want to know:
//
//   spectrum    log frequency, the peak in each of 128 bands, with a
//               trace that holds the loudest thing seen since reset
//   levels      true peak (ITU-R BS.1770, four times oversampled), sample
//               peak and rms per channel, the highest value held
//   loudness    momentary, short term, integrated and loudness range -
//               BS.1770 / EBU R128, K-weighted and gated
//   imager      a goniometer of the stereo image, with correlation,
//               balance and width
//
//   Insight(\main, 0, 2);              // bus 0, two channels: the main out
//   Insight.at(\main).gui;             // the window
//   Insight(\verb, \reverb);           // a BusManager name, followed from then on
//   Insight(\pads, OneShot(\pads));    // a module: its tapBus
//   Insight.open;                      // the \main insight, window and all
//
//   i = Insight.at(\main);
//   i.loudness.integrated;             // -> -16.8, LUFS since reset
//   i.maxTruePeaks.collect(_.ampdb);   // -> [-0.3, -0.5], dBTP since reset
//   i.reset;                           // integrate again, drop every held maximum
//
// One synth per insight does the per sample work - the K-weighting
// filter and its 100 ms blocks, the interpolation for true peak, one
// FFT, a snapshot of the stereo pair - and the language reads its
// replies and its buffers. That costs a few messages a second while
// running and nothing while stopped, and it is stopped until something
// asks: opening the window starts it, closing the window stops it again.
//
// An insight only reads. Nothing is allocated on the bus it watches and
// nothing is freed. BusManager is looked up as \BusManager.asClass and
// never referenced directly: without it, a bus is an index.
// =====================================================================

Insight {
	classvar <all;							// name -> insight
	classvar defs, idCounter;
	classvar <>meterRate = 25;				// level replies a second
	classvar <>loudnessRate = 10;			// loudness blocks a second: the spec's 100 ms hop
	classvar <>pollRate = 15;				// frames a second read back from the server
	classvar <fftSizes, <sources, <tilts;

	var <name, <server, <id;
	var <bus;								// index of the bus read; nil while a name is unknown
	var <busName;							// the BusManager name followed, nil if set by index
	var <channels;
	var <running = false;
	var <fftSize = 4096, <source = \sum, <imagerFrames = 1024;
	var <tilt = 3, <smoothing = 0, <hold = true, <spectrumFloor = -96;
	var <target = -14, <ceiling = -1;		// LUFS to aim at, dBTP not to pass
	var <loudness, <spectrum;
	var <peaks, <truePeaks, <rms;			// amplitude per channel, from the last reply
	var <maxPeaks, <maxTruePeaks;			// the highest since reset
	var <correlation = 0, <width = 0, <balance = 0;
	var <overs = 0, overNow = false;		// true peak past the ceiling, counted per crossing
	var <imagerData, <imagerFrame = 0;		// [l, r, l, r ...] of the last snapshot, and a counter
	var <history, <>historySeconds = 60;	// short term loudness, one value per block
	var <sampleRate, <lastReply = 0;
	var synth, group, fftBuf, imgBuf, starting = false;
	var levelFunc, loudFunc, setnFunc, poller;
	var specFrame, specChunks = 0, specFetching = false, specSent = 0, specTaken = 0;
	var imgFrame, imgChunks = 0, imgFetching = false, imgSent = 0;
	var bootFunc, treeFunc, quitFunc, hooksAdded = false;
	var lostName = false;
	var <guiObj;

	*initClass {
		all = IdentityDictionary.new;
		defs = IdentityDictionary.new;
		idCounter = 0;
		fftSizes = [1024, 2048, 4096, 8192, 16384];
		sources = [\sum, \left, \right, \mid, \side];
		tilts = [0, 1.5, 3, 4.5, 6];
	}

	// ------------------------------------------------------------ registry

	// A name, and one insight under it. Making another under a taken name
	// frees the old one first, so re-evaluating a setup file replaces the
	// insight rather than stacking a second synth on the bus.
	*new { |name = \main, bus = 0, channels, server|
		^super.new.init(name, bus, channels, server)
	}

	*at { |name| ^all[name.asSymbol] }
	*names { ^all.keys.asArray.sort }
	*size { ^all.size }
	*freeAll { all.values.copy.do(_.free) }

	// the insight on the main out - made the first time it is asked for
	*main { |server|
		^this.at(\main) ?? { this.new(\main, 0, 2, server) }
	}

	// the main out, window and all: what the button in ServerView does
	*open { |server|
		var ins = this.main(server);
		ins.gui;
		^ins
	}

	*gui { |name = \main|
		^(this.at(name) ?? { this.main }).gui
	}

	*prBusRegistry { ^\BusManager.asClass }

	init { |argName, argBus, argChannels, argServer|
		name = argName.asSymbol;
		server = argServer ? Server.default;
		idCounter = idCounter + 1;
		id = idCounter;
		all[name] !? { |old| old.free };
		all[name] = this;
		channels = (argChannels ? 2).asInteger.clip(1, 64);
		this.prResetArrays;
		loudness = InsightLoudness(channels);
		history = [];
		this.prEnsureSetup;
		this.prSetBus(argBus ? 0, argChannels);
		^this
	}

	free {
		this.stop;
		this.prRemoveHooks;
		this.class.prBusRegistry !? (_.removeDependant(this));
		guiObj !? (_.close);
		if (all[name] === this) { all.removeAt(name) };
		this.changed(\freed);
		^this
	}

	// ----------------------------------------------------------------- bus

	// An index, a Bus, a BusManager name, or a module. A name binds: the
	// insight follows it when the bus behind it moves, which it does after
	// a server boot. An index unbinds again. A name that resolves to
	// nothing changes nothing - unless there is no bus yet, in which case
	// the name is kept and followed for when it turns up.
	bus_ { |val| ^this.prSetBus(val, nil) }

	channels_ { |n|
		n = (n ? channels).asInteger.clip(1, 64);
		if (n == channels) { ^this };
		channels = n;
		this.prChannelsChanged;
		this.changed(\bus);
		^this
	}

	isResolved { ^bus.notNil }

	// what the window shows: the name followed, or the index
	busLabel {
		^busName !? (_.asString) ?? { bus !? (_.asString) ? "?" }
	}

	prSetBus { |val, chans|
		var res = this.prResolve(val);
		var newChannels, moved;
		if (res.isNil) { ^this };
		busName = res[2];
		lostName = false;
		this.prUpdateRegistryLink;
		newChannels = (chans ? res[1] ? channels).asInteger.clip(1, 64);
		moved = res[0] != bus;
		bus = res[0];
		if (newChannels != channels) {
			channels = newChannels;
			this.prChannelsChanged;
		} {
			if (moved) { this.prBusMoved };
		};
		this.changed(\bus);
		^this
	}

	// [index, channels, name] - each nil where the value has nothing to say
	// about it - or nil when nothing can be made of the value at all
	prResolve { |val|
		var reg = this.class.prBusRegistry, entry, tap, index;
		case
		{ val.isNumber } { ^[val.asInteger.max(0), nil, nil] }
		{ val.isKindOf(Bus) } {
			if (val.rate != \audio) {
				("Insight: % is a control bus - there is nothing to analyse".format(val)).warn;
				^nil
			};
			^[val.index, val.numChannels, nil]
		}
		{ val.isKindOf(Symbol) or: { val.isKindOf(String) } } {
			if (reg.isNil) {
				("Insight: % is a name, and BusManager is not installed"
					.format(val.asCompileString)).warn;
				^nil
			};
			entry = reg.at(val.asSymbol);
			if (entry.isNil) {
				if (bus.isNil) {
					("Insight: no bus called % yet - following it for when there is"
						.format(val.asCompileString)).postln;
					^[nil, nil, val.asSymbol]
				};
				("Insight: % is not a registered bus name - staying on bus %"
					.format(val.asCompileString, bus)).warn;
				^nil
			};
			if (entry.isAudio.not) {
				("Insight: % is a control bus - there is nothing to analyse"
					.format(entry.name)).warn;
				^nil
			};
			^[entry.index, entry.numChannels, entry.name]
		}
		{ val.respondsTo(\tapBus) } {
			tap = val.tapBus;
			if (tap.isKindOf(Bus)) { ^[tap.index, tap.numChannels, nil] };
			^this.prResolve(tap)
		}
		{ true } {
			index = val.tryPerform(\asControlInput);
			if (index.isNumber) { ^[index.asInteger, nil, nil] };
			("Insight: % cannot be read as a bus".format(val.asCompileString)).warn;
			^nil
		};
	}

	// only an insight following a name listens to the registry
	prUpdateRegistryLink {
		this.class.prBusRegistry !? { |reg|
			if (busName.notNil) { reg.addDependant(this) } { reg.removeDependant(this) };
		};
	}

	// The registry changed: an insight following a name goes with it. A
	// name that has gone leaves it where it was, and picks up again when
	// the name comes back.
	update { |theChanged, what|
		var reg, entry;
		if (what != \buses or: { busName.isNil }) { ^this };
		reg = this.class.prBusRegistry;
		entry = reg !? { reg.at(busName) };
		if (entry.isNil or: { entry.isAudio.not }) {
			if (lostName.not) {
				lostName = true;
				("Insight %: the bus % has gone - % until it comes back".format(
					name, busName.asCompileString,
					if (bus.isNil) { "waiting" } { "staying on bus " ++ bus })).postln;
			};
			^this
		};
		lostName = false;
		if (entry.numChannels != channels) {
			bus = entry.index;
			channels = entry.numChannels;
			this.prChannelsChanged;
			this.changed(\bus);
		} {
			if (entry.index != bus) {
				bus = entry.index;
				this.prBusMoved;
				this.changed(\bus);
			};
		};
	}

	prBusMoved {
		if (synth.notNil) { synth.set(\in, bus) } { if (running) { this.prStart } };
	}

	// the def is built per channel count, so the synth has to be made again
	prChannelsChanged {
		loudness = InsightLoudness(channels);
		this.prResetArrays;
		this.prRestartNodes;
	}

	// ------------------------------------------------------------- running

	run {
		if (running) { ^this };
		running = true;
		this.prStart;
		this.changed(\running);
		^this
	}

	stop {
		if (running.not) { ^this };
		running = false;
		this.prStopNodes;
		this.changed(\running);
		^this
	}

	running_ { |flag| if (flag == true) { this.run } { this.stop } }

	// the synth is up and its replies are arriving
	isAlive { ^synth.notNil and: { (Main.elapsedTime - lastReply) < 1.0 } }

	// Integrate again from now, and drop every held maximum: the loudness
	// statistics, the highest peaks, the over count, the spectrum's held
	// trace and the history.
	reset {
		loudness.reset;
		maxPeaks = 0.0 ! channels;
		maxTruePeaks = 0.0 ! channels;
		overs = 0;
		overNow = false;
		spectrum !? (_.resetMaxima);
		history = [];
		this.changed(\reset);
		^this
	}

	// the highest true peak of any channel since reset, in dBTP
	maxTruePeakDb { ^(maxTruePeaks.maxItem ? 0).ampdb }

	maxPeakDb { ^(maxPeaks.maxItem ? 0).ampdb }

	// ------------------------------------------------------------ settings

	fftSize_ { |n|
		n = n.asInteger;
		if (fftSizes.includes(n).not) {
			("Insight: fftSize must be one of %".format(fftSizes)).warn;
			^this
		};
		if (n == fftSize) { ^this };
		fftSize = n;
		spectrum = nil;						// made again at the next start, for the new size
		this.prRestartNodes;
		this.changed(\settings);
		^this
	}

	// what the spectrum is of: \sum of every channel, \left, \right, \mid, \side
	source_ { |sym|
		sym = sym.asSymbol;
		if (sources.includes(sym).not) {
			("Insight: source must be one of %".format(sources)).warn;
			^this
		};
		source = sym;
		synth !? (_.set(\specSrc, sources.indexOf(sym)));
		this.changed(\settings);
		^this
	}

	// how many frames the goniometer shows at once
	imagerFrames_ { |n|
		n = n.asInteger.clip(256, 8192);
		if (n == imagerFrames) { ^this };
		imagerFrames = n;
		this.prRestartNodes;
		this.changed(\settings);
		^this
	}

	// dB per octave added to the drawn spectrum, so that pink noise reads
	// flat at 3 - a display setting, not a measurement
	tilt_ { |db|
		tilt = (db ? 0).clip(0, 12);
		this.changed(\settings);
		^this
	}

	smoothing_ { |amount|
		smoothing = (amount ? 0).clip(0, 0.99);
		spectrum !? (_.smoothing_(smoothing));
		this.changed(\settings);
		^this
	}

	hold_ { |flag|
		hold = flag == true;
		spectrum !? { |sp| sp.hold = hold; if (hold.not) { sp.resetMaxima } };
		this.changed(\settings);
		^this
	}

	spectrumFloor_ { |db|
		spectrumFloor = (db ? -96).clip(-120, -48);
		spectrum !? (_.floorDb_(spectrumFloor));
		this.changed(\settings);
		^this
	}

	target_ { |lufs|
		target = (lufs ? -14).clip(-60, 0);
		this.changed(\settings);
		^this
	}

	ceiling_ { |dbtp|
		ceiling = (dbtp ? -1).clip(-20, 6);
		this.changed(\settings);
		^this
	}

	// ----------------------------------------------------------------- gui

	gui { |x, y|
		if (guiObj.isNil) { guiObj = InsightGUI(this, x, y) } { guiObj.front };
		^guiObj
	}

	prGuiClosed { guiObj = nil }

	// ------------------------------------------------------------- private

	prResetArrays {
		peaks = 0.0 ! channels;
		truePeaks = 0.0 ! channels;
		rms = 0.0 ! channels;
		maxPeaks = 0.0 ! channels;
		maxTruePeaks = 0.0 ! channels;
		correlation = 0;
		width = 0;
		balance = 0;
		overs = 0;
		overNow = false;
		imagerData = nil;
	}

	// what the meters show when nothing is arriving
	prResetLive {
		peaks = 0.0 ! channels;
		truePeaks = 0.0 ! channels;
		rms = 0.0 ! channels;
		correlation = 0;
		width = 0;
		balance = 0;
		imagerData = nil;
		spectrum !? (_.clear);
	}

	prRestartNodes {
		if (running.not) { ^this };
		this.prStopNodes;
		this.prStart;
	}

	// The def has to be on the server and the buffers allocated before the
	// synth asks for them, so everything waits on one sync. The sample rate
	// arrives with the first status reply after a boot, and the block
	// lengths in the def depend on it, so that is waited for too.
	prStart {
		if (running.not or: { starting } or: { bus.isNil } or: { server.serverRunning.not }) {
			^this
		};
		starting = true;
		fork {
			var sr, def, tries = 0;
			while { server.sampleRate.isNil and: { tries < 30 } } { 0.1.wait; tries = tries + 1 };
			sr = (server.sampleRate ? server.options.sampleRate ? 48000).asFloat;
			if (server.serverRunning and: { running }) {
				sampleRate = sr;
				if (spectrum.isNil or: { spectrum.fftSize != fftSize }
					or: { spectrum.sampleRate != sr }) {
					spectrum = InsightSpectrum(fftSize, sr)
						.smoothing_(smoothing).hold_(hold).floorDb_(spectrumFloor);
				};
				def = this.class.prDef(channels, sr);
				this.class.prSendDef(def, server);
				if (fftBuf.notNil and: { fftBuf.numFrames != fftSize }) { fftBuf.free; fftBuf = nil };
				if (imgBuf.notNil and: { imgBuf.numFrames != imagerFrames }) { imgBuf.free; imgBuf = nil };
				fftBuf ?? { fftBuf = Buffer.alloc(server, fftSize, 1) };
				imgBuf ?? { imgBuf = Buffer.alloc(server, imagerFrames, 2) };
				server.sync;
			};
			starting = false;
			// running may have gone off, or the server away, while we waited
			if (running and: { def.notNil } and: { server.serverRunning } and: { synth.isNil }
				and: { fftBuf.notNil } and: { imgBuf.notNil } and: { bus.notNil }) {
				// at the tail of the root node, after the server's own volume
				// and any chain of effects on the main out - so the picture
				// is of what actually leaves the server
				group ?? { group = Group(RootNode(server), \addToTail) };
				specFrame = Array.newClear(spectrum.fetchCount);
				imgFrame = Array.newClear(imagerFrames * 2);
				specFetching = false;
				imgFetching = false;
				specTaken = Main.elapsedTime;
				synth = Synth(def.name, [
					\in, bus, \fft, fftBuf.bufnum, \img, imgBuf.bufnum,
					\specSrc, sources.indexOf(source) ? 0
				], group, \addToTail);
				this.prStartResponders;
				this.prStartPoller;
			};
		};
		^this
	}

	prStopNodes {
		this.prStopPolling;
		if (server.serverRunning) {
			synth !? (_.free);
			group !? (_.free);
			fftBuf !? (_.free);
			imgBuf !? (_.free);
		};
		synth = nil;
		group = nil;
		fftBuf = nil;
		imgBuf = nil;
		this.prResetLive;
	}

	prStopPolling {
		poller !? (_.stop);
		poller = nil;
		this.prFreeResponders;
		specFetching = false;
		imgFetching = false;
	}

	// ------------------------------------------------------------ replies

	prStartResponders {
		this.prFreeResponders;
		levelFunc = OSCFunc({ |msg|
			if (synth.notNil and: { msg[1] == synth.nodeID }) { this.prLevels(msg.copyToEnd(3)) };
		}, '/insightLevel', server.addr);
		loudFunc = OSCFunc({ |msg|
			if (synth.notNil and: { msg[1] == synth.nodeID }) { this.prLoud(msg.copyToEnd(3)) };
		}, '/insightLoud', server.addr);
		setnFunc = OSCFunc({ |msg| this.prChunk(msg) }, '/b_setn', server.addr);
	}

	prFreeResponders {
		levelFunc !? (_.free);
		loudFunc !? (_.free);
		setnFunc !? (_.free);
		levelFunc = loudFunc = setnFunc = nil;
	}

	// [peak per channel, true peak per channel, rms per channel, correlation, width]
	prLevels { |v|
		var n = channels, over, l, r, tot;
		if (v.size < ((3 * n) + 2)) { ^this };
		peaks = v.copyRange(0, n - 1);
		truePeaks = v.copyRange(n, (2 * n) - 1);
		rms = v.copyRange(2 * n, (3 * n) - 1);
		correlation = v[3 * n].clip(-1, 1);
		width = v[(3 * n) + 1].clip(0, 4);
		n.do { |i|
			if (peaks[i] > maxPeaks[i]) { maxPeaks[i] = peaks[i] };
			if (truePeaks[i] > maxTruePeaks[i]) { maxTruePeaks[i] = truePeaks[i] };
		};
		if (n > 1) {
			l = rms[0];
			r = rms[1];
			tot = l + r;
			balance = if (tot > 1e-6) { (r - l) / tot } { 0 };
		};
		// an over is a crossing of the ceiling, not every reply spent above it
		over = truePeaks.maxItem.ampdb > ceiling;
		if (over and: { overNow.not }) { overs = overs + 1 };
		overNow = over;
		lastReply = Main.elapsedTime;
	}

	// one 100 ms block: the mean square of the K-weighted signal, per channel
	prLoud { |v|
		var max = (historySeconds * loudnessRate).asInteger.max(10);
		if (v.size < channels) { ^this };
		loudness.add(v.keep(channels));
		history = history.add(loudness.shortTerm);
		if (history.size > max) { history = history.drop(history.size - max) };
	}

	// --------------------------------------------------------- the buffers

	prStartPoller {
		poller !? (_.stop);
		poller = Routine({
			loop {
				(1 / pollRate.clip(1, 60)).wait;
				if (running and: { synth.notNil } and: { server.serverRunning }) { this.prPoll };
			};
		}).play(AppClock);
	}

	// Ask for the FFT frame and the stereo snapshot, in as few messages as
	// the server will answer - b_getn tops out at 1633 values - and all
	// of them in one bundle. The snapshot is triggered again in the same
	// bundle, after it has been read, so the next poll finds a fresh one.
	// A fetch that never comes back is not chased: the next is along in a
	// moment.
	prPoll {
		var msgs = [], now = Main.elapsedTime;
		if (specFetching and: { (now - specSent) > 1 }) { specFetching = false };
		if (imgFetching and: { (now - imgSent) > 1 }) { imgFetching = false };
		if (specFetching.not and: { fftBuf.notNil } and: { spectrum.notNil }) {
			specChunks = 0;
			this.prGetnMsgs(fftBuf.bufnum, spectrum.fetchCount).do { |m|
				msgs = msgs.add(m);
				specChunks = specChunks + 1;
			};
			specFetching = true;
			specSent = now;
		};
		if (imgFetching.not and: { imgBuf.notNil }) {
			imgChunks = 0;
			this.prGetnMsgs(imgBuf.bufnum, imagerFrames * 2).do { |m|
				msgs = msgs.add(m);
				imgChunks = imgChunks + 1;
			};
			msgs = msgs.add([\n_set, synth.nodeID, \t_snap, 1]);
			imgFetching = true;
			imgSent = now;
		};
		if (msgs.notEmpty) { server.listSendBundle(nil, msgs) };
	}

	prGetnMsgs { |bufnum, count|
		var pos = 0, msgs = [];
		while { pos < count } {
			var n = min(1633, count - pos);
			msgs = msgs.add([\b_getn, bufnum, pos, n]);
			pos = pos + n;
		};
		^msgs
	}

	// [\b_setn, bufnum, start, count, values...] - one chunk of a frame
	prChunk { |msg|
		var buf = msg[1], now;
		if (fftBuf.notNil and: { buf == fftBuf.bufnum }) {
			if (specFetching.not or: { specFrame.isNil }) { ^this };
			specFrame = specFrame.overWrite(msg.copyToEnd(4), msg[2]);
			specChunks = specChunks - 1;
			if (specChunks <= 0) {
				specFetching = false;
				now = Main.elapsedTime;
				spectrum !? (_.take(specFrame, now - specTaken));
				specTaken = now;
			};
			^this
		};
		if (imgBuf.notNil and: { buf == imgBuf.bufnum }) {
			if (imgFetching.not or: { imgFrame.isNil }) { ^this };
			imgFrame = imgFrame.overWrite(msg.copyToEnd(4), msg[2]);
			imgChunks = imgChunks - 1;
			if (imgChunks <= 0) {
				imgFetching = false;
				imagerData = imgFrame.copy;
				imagerFrame = imagerFrame + 1;
			};
		};
	}

	// ------------------------------------------------------------ the def

	// One def per channel count and sample rate, built when first needed
	// and kept. Building does not send: prSendDef does that, and the synth
	// waits on a sync afterwards.
	*prDef { |channels, sr|
		var key = "insight_%_%".format(channels, sr.asInteger).asSymbol;
		^defs[key] ?? {
			defs[key] = SynthDef(key, {
				// InFeedback, not In: right wherever the synth sits relative
				// to whatever writes the bus, at the cost of one block of
				// delay, which no meter can show
				var sigs = InFeedback.ar(\in.kr(0), channels).asArray;
				var levelTrig = Impulse.kr(meterRate);
				var loudTrig = Impulse.kr(loudnessRate);
				var left = sigs[0], right = sigs[1.min(channels - 1)];
				var mid = (left + right) * 0.5, side = (left - right) * 0.5;
				var sum = Mix(sigs) / channels;
				var n100 = (sr * 0.1).round.asInteger.max(1);
				var peak, tp, rmsSig, ll, rr, lr, corr, mm, ss, width, kw, blocks, specSig;

				// levels: the peak of every reply interval, and an rms that
				// integrates over about 300 ms, the way a vu does
				peak = Peak.kr(sigs, levelTrig);
				tp = sigs.collect { |ch| Peak.kr(this.prTruePeak(ch, sr), levelTrig) };
				rmsSig = sigs.collect { |ch| Lag.ar(ch * ch, 0.3).sqrt };

				// the image: correlation of the first pair, and side against mid
				ll = Lag.ar(left * left, 0.3);
				rr = Lag.ar(right * right, 0.3);
				lr = Lag.ar(left * right, 0.3);
				corr = lr / (ll * rr).max(1e-12).sqrt;
				mm = Lag.ar(mid * mid, 0.3);
				ss = Lag.ar(side * side, 0.3);
				width = (ss / mm.max(1e-12)).sqrt;

				// loudness: the K filter of BS.1770 - a high shelf of +4 dB
				// from 1681 Hz, then a high pass at 38 Hz - and the mean
				// square of the last 100 ms, per channel
				kw = sigs.collect { |ch|
					BHiPass.ar(BHiShelf.ar(ch, 1681.974, 1, 3.999843), 38.13547, 1 / 0.500327)
				};
				blocks = kw.collect { |ch| RunningSum.ar(ch * ch, n100) / n100 };

				// the spectrum and the snapshot: buffers the language reads
				specSig = Select.ar(\specSrc.kr(0), [sum, left, right, mid, side]);
				FFT(\fft.kr(0), specSig, 0.5, 1);
				RecordBuf.ar([left, right], \img.kr(0), 0, 1, 0, 1, 0, \t_snap.kr(0));

				SendReply.kr(levelTrig, '/insightLevel',
					peak ++ tp ++ A2K.kr(rmsSig) ++ [A2K.kr(corr), A2K.kr(width)]);
				SendReply.kr(loudTrig, '/insightLoud', A2K.kr(blocks));
			});
			// `dict[key] = x` answers the dict, not x - so hand back the def
			defs[key]
		}
	}

	// True peak the way BS.1770 asks for it: the signal interpolated to four
	// times the rate, and the largest sample of that. The interpolation is
	// a windowed sinc split into its phases; phase 0 is the sample itself,
	// so the sample peak stands in for it. Twice the rate is enough above
	// 96 kHz, and none at all above 192.
	*prTruePeak { |sig, sr|
		var over = case { sr < 96000 } { 4 } { sr < 192000 } { 2 } { true } { 1 };
		var phases, delayed, outs;
		if (over == 1) { ^sig.abs };
		phases = this.prTruePeakPhases(over);
		delayed = Array.newClear(12);
		delayed[0] = sig;
		11.do { |i| delayed[i + 1] = Delay1.ar(delayed[i]) };
		outs = phases.collect { |coefs|
			Mix(12.collect { |j| delayed[j] * coefs[j] })
		};
		^([sig] ++ outs).abs.reduce(\max)
	}

	// over - 1 sets of twelve taps, one per phase, each summing to one
	*prTruePeakPhases { |over = 4|
		var taps = 12;
		var n = (taps * over) + 1;
		var centre = (n - 1) / 2;
		var h = Array.fill(n, { |i|
			var x = (i - centre) / over;
			var sinc = if (x == 0) { 1.0 } { sin(pi * x) / (pi * x) };
			sinc * (0.5 - (0.5 * cos(2pi * i / (n - 1))))
		});
		^(1..(over - 1)).collect { |p|
			var coefs = Array.fill(taps, { |j| h[(over * j) + p] });
			coefs / coefs.sum
		}
	}

	// SynthDef:add reaches the default server; an insight on any other
	// server has to be sent to as well
	*prSendDef { |def, server|
		def.add;
		if (server != Server.default) { def.send(server) };
		^def
	}

	// --------------------------------------------------- server lifecycle

	prEnsureSetup {
		if (hooksAdded) { ^this };
		hooksAdded = true;
		bootFunc = { this.prOnBoot };
		treeFunc = { this.prOnTree };			// also runs after cmd-period
		quitFunc = { this.prOnQuit };
		ServerBoot.add(bootFunc, server);
		ServerTree.add(treeFunc, server);
		ServerQuit.add(quitFunc, server);
		^this
	}

	prRemoveHooks {
		if (hooksAdded.not) { ^this };
		ServerBoot.remove(bootFunc, server);
		ServerTree.remove(treeFunc, server);
		ServerQuit.remove(quitFunc, server);
		hooksAdded = false;
		^this
	}

	// the boot took every node and every buffer with it; the tree hook
	// that follows puts them back
	prOnBoot {
		synth = nil;
		group = nil;
		fftBuf = nil;
		imgBuf = nil;
		sampleRate = nil;
	}

	// the tree was rebuilt - after a boot, or after cmd-period took every node
	prOnTree {
		synth = nil;
		group = nil;
		if (running) { this.prStart };
	}

	prOnQuit {
		synth = nil;
		group = nil;
		fftBuf = nil;
		imgBuf = nil;
		this.prStopPolling;
		this.prResetLive;
		this.changed(\running);
	}

	printOn { |stream|
		stream << "Insight(" << name.asCompileString << ", bus " << this.busLabel
			<< ", " << channels << "ch" << (if (running) { ", running" } { "" }) << ")"
	}
}
