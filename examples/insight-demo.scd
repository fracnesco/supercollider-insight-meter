// =====================================================================
// Insight demo - evaluate one block at a time (ctrl/cmd + enter)
// =====================================================================

// 1. boot, and open on the main out -----------------------------------
s.boot;

Insight.open;

// The window on bus 0. Nothing is going out yet, so it is quiet. The
// analysis is running because the window is open; close it and it stops.


// 2. something to look at ---------------------------------------------
(
x = {
	var sig = Splay.ar(
		SinOsc.ar([110, 220, 440, 660, 990] * LFNoise0.kr(3).range(1, 1.01), 0, 0.08)
	);
	sig + (PinkNoise.ar(0.02) ! 2)
}.play;
)

// The spectrum fills in, the meters move, the goniometer draws the
// image. Hover the spectrum for the frequency and note under the mouse;
// click it to clear the held (orange) trace; scroll it to move the range.


// 3. reading it in code -----------------------------------------------
i = Insight.at(\main);

i.loudness.momentary;        // LUFS, the last 400 ms
i.loudness.integrated;       // LUFS, everything since reset, gated
i.loudness.range;            // LU
i.maxTruePeakDb;             // the highest true peak, dBTP
i.correlation;               // -1 .. +1
i.width;                     // 0 mono .. 1 spread


// 4. the marks --------------------------------------------------------
i.target = -14;              // aim for -14 LUFS: a line on the bars and history
i.ceiling = -1;              // a true peak past -1 dBTP is an over

// push it hard and watch the over count climb
(
y = { SinOsc.ar(1000, 0, 0.98) ! 2 }.play;
)
i.overs;                     // more than zero now
y.free;

i.reset;                     // start the integration over, clear the maxima


// 5. the spectrum settings --------------------------------------------
i.fftSize = 8192;            // resolves the low end better, moves slower
i.source = \side;            // analyse the side signal instead of the sum
i.source = \sum;
i.tilt = 3;                  // pink noise reads flat at 3 dB/oct
i.smoothing = 0.6;           // a slower, steadier trace


// 6. pointing it somewhere else ---------------------------------------
// any bus works - a reverb send, a hardware input, a module's bus

~fx = Bus.audio(s, 2);
i.bus = ~fx;
(
z = { Out.ar(~fx.index, PinkNoise.ar(0.1) ! 2) }.play;
)
// now the insight is on the fx bus
z.free;
i.bus = 0;                   // back to the main out


// 7. with BusManager (optional) ---------------------------------------
// if BusManager is installed, a name works and is followed from then on:

// BusManager.add(\reverb, 2);
// i.bus = \reverb;           // follows the name; survives a reboot
// i.busName;                 // -> reverb


// 8. tidying up -------------------------------------------------------
x.free;
Insight.freeAll;
