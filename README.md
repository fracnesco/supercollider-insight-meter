# Insight

**A sound engineer's window on a bus.** A SuperCollider extension.

Point it at a bus - by index, by BusManager name, by Bus, or at a module - and
it shows what a mastering chain wants to know: the spectrum, the levels with
true peak, loudness the way EBU R128 means it, and the stereo image. One synth
does the per-sample work and the window draws what it finds.

```supercollider
s.boot;
Insight(\main, 0, 2);          // bus 0, two channels: the main out
Insight.at(\main).gui;         // the window

// or, in one line, on the main out:
Insight.open;
```

It needs no plugins. Its window is drawn with the widgets every one of my effect
windows shares - FxPalette, FxKnob, FxHeader and the rest - which live in the
**ParaEQ** quark (`sc-eq`), so install that first (see [Install](#install)). If **BusManager** happens
to be installed alongside it, an insight can be pointed at a bus by name and
follows that name from then on. If **ServerPanel** is installed, its meter
section grows a button that opens an insight on the main out - see
[Integration](#integration) below.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific
apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## What it measures

- **spectrum** - log frequency, the peak in each of 128 bands, with three
  traces: what is there now, what was there a moment ago, and the loudest
  thing seen in each band since the last reset. Tilt it so pink noise reads
  flat; hover it for the frequency, the note and the level under the mouse.
- **levels** - true peak (ITU-R BS.1770, four times oversampled), sample peak
  and rms per channel, each holding its highest value until reset. A true peak
  past the ceiling is counted as an over.
- **loudness** - momentary (400 ms), short term (3 s), integrated and loudness
  range, all K-weighted and gated the way BS.1770 and EBU R128 / Tech 3342
  spell out. The last minute of short term loudness is drawn as a graph.
- **image** - a goniometer of the stereo pair, with correlation, balance and
  width, so a mono fold-down or a phase problem is visible before it is a
  surprise.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-eq.git");                       // the window's widgets
Quarks.install("https://github.com/fracnesco/supercollider-insight-meter.git");
thisProcess.recompile;
```

`Quarks.install` clones the repository into your `downloaded-quarks` folder and
puts it on the class path; the recompile is what makes the classes exist, and is
the same thing as `Language -> Recompile Class Library` (ctrl/cmd + shift + L).

Afterwards:

```supercollider
Quarks.update("supercollider-insight-meter");       // pull the newest version, then recompile
Quarks.uninstall("supercollider-insight-meter");    // take it off the class path
Quarks.gui;                        // see what is installed
```

The quark goes by the name of the repository, `supercollider-insight-meter`; the class inside it
is `Insight`.

### From a clone

If you would rather have a working copy you can edit, clone it wherever you keep
things and link it into the extensions folder:

```sh
git clone https://github.com/fracnesco/supercollider-insight-meter.git
```

There is an `install.scd` in the repository that does the linking for you: open
it and evaluate it, then recompile the class library. It symlinks the folder
into `Platform.userExtensionDir` and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the folder into the
extensions folder by hand - `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a
duplicate class error at compile time.

### Check that it took

```supercollider
Insight.open;      // the main out, window and all
Insight.names;     // every insight, by name
```

Select `Insight` and press cmd/ctrl + d for the help file, and see
[examples/insight-demo.scd](examples/insight-demo.scd) for a walkthrough you can
evaluate a block at a time.

## Pointing it at a bus

One insight per name, the way there is one server. Everything you reach it by is
on the class - there is one registry, and `Insight.at` looks a name up again
from anywhere.

```supercollider
Insight(\main, 0, 2);              // an index and a channel count
Insight(\verb, Bus.audio(s, 2));   // a Bus something else made
Insight(\pads, OneShot(\pads));    // a module: its tapBus is read

Insight.names;                     // every insight, in name order
Insight.at(\main);                 // look one up
Insight.at(\main).bus_(16);        // point it somewhere else
Insight.freeAll;
```

Creating an insight under a name that is taken frees the old one first, so
re-evaluating a setup file replaces the insight rather than stacking a second
synth on the bus. Nothing is ever allocated on the bus it watches, and nothing
is freed: an insight only reads, which is what makes it safe to point at
anything - a hardware input, the main out, the bus a reverb lives on.

### Buses by name

With [BusManager](https://github.com/fracnesco/sc-bus-manager) installed, the
bus can be a **name**:

```supercollider
BusManager.add(\reverb, 2);
Insight(\verb, \reverb);           // follows the name from now on
Insight.at(\verb).bus_(\out3_4);
```

An insight set to a name **follows** it: when the bus behind it moves - which it
does when the server reboots and the bus allocator is reset - the insight goes
with it and does not have to be told. Setting the bus back to a plain index
unbinds it, and `busName` says which name an insight is following, nil if none.
A name that resolves to nothing changes nothing - the insight keeps the bus it
had - unless there is no bus yet, in which case the name is kept and waited for.

Without BusManager installed a name is simply not a bus and says so; everything
else works by index.

## Reading it in code

The window is one way to read an insight; the accessors are another, for a check
or a script.

```supercollider
i = Insight.at(\main);
i.run;                             // start the analysis synth

i.loudness.integrated;             // -> -16.8, LUFS since reset
i.loudness.range;                  // -> 6.2, LU
i.maxTruePeakDb;                   // -> -0.3, the highest true peak, dBTP
i.overs;                           // times it went past the ceiling
i.truePeaks.collect(_.ampdb);      // dBTP per channel, right now
i.rms.collect(_.ampdb);            // dBFS per channel
i.correlation;                     // -1 .. +1
i.width;                           // 0 is mono, 1 is fully spread

i.reset;                           // integrate again, drop every held maximum
i.stop;
```

`loudness` is an [InsightLoudness](Classes/InsightLoudness) - the BS.1770
integrator - and `spectrum` an [InsightSpectrum](Classes/InsightSpectrum), each
usable on its own.

## Running, and the cost

The analysis is one synth doing the per-sample work - the K-weighting filter and
its 100 ms blocks, the interpolation for true peak, one FFT, a snapshot of the
stereo pair - and the language reading its replies and its buffers, a few
messages a second. It is **off until something asks for it**: opening the window
starts it, closing the window stops it again, unless it was already running.

```supercollider
i.run;                             // start it without a window
i.running;                         // -> true
i.isAlive;                         // -> true once its replies are arriving
i.stop;
```

**Freeze** on the window holds the picture without stopping the analysis, for
reading a number off a moment that has passed.

## The settings

```supercollider
i.fftSize = 8192;                  // 1024 .. 16384: bigger resolves the low end
i.source = \mid;                   // what the spectrum is of: sum, left, right, mid, side
i.tilt = 3;                        // dB/oct added to the drawing, so pink noise reads flat
i.smoothing = 0.6;                 // 0 fast .. 0.95 slow, a display average
i.hold = true;                     // keep the loudest-per-band trace

i.target = -14;                    // the loudness aimed at: a line on the bars and graph
i.ceiling = -1;                    // a true peak past this dBTP is an over
i.preset = 'ebu r128 -23';         // a target and a ceiling at once: Insight.presetNames
i.resetPeaks;                      // drop what is held, go on integrating
```

`target` and `ceiling` are marks, not limits: nothing is changed on the bus, they
are only drawn, so you can see how far from -14 LUFS and -1 dBTP the signal is.

## The window

`Insight.at(\main).gui`, or `Insight.gui(\main)`. The same frame as every effect
window of mine - the header, the panes, the hint along the bottom - with an
analyser's face: a dashboard of titled panes, and no mix or output, since
nothing passes through it.

```
 (o) INSIGHT  < streaming -14  v >  save  ...              freeze  reset  [bus 0 · 2 ch v]
 +- spectrum  [sum v] [+3 dB/oct v] fast|medium|slow  max  [fft 4096 v] -+ +- levels · loudness -+
 |                                                                        | | -4  -5     -13 -14  |
 |   now (blue), a moment ago (white), the loudest held (dashed)          | |  L   R      M   S   |
 |   20    50   100   200   500   1k    2k    5k   10k   20k              | |  ---- ceiling  [==] |
 +------------------------------------------------------------------------+ +---------------------+
 +- loudness · short term  1 min|5 min -----+ +- stereo ------------+ +- since reset · 01:23 ------+
 |  [== target ==]  ...integrated...  ~~~   | |  goniometer   corr  | | integrated                 |
 |  -60 s                           now     | |  L ---|--- R        | | -15.3 LUFS  1.3 LU under   |
 +------------------------------------------+ +---------------------+ | short true peak range ...  |
 hover over a pane or a reading ...                bus 0 · 2 ch · running | (target)   (ceiling)      |
```

- The **header**: the power button runs the analysis (off, it costs nothing).
  The presets are **loudness targets** - streaming -14, apple music -16,
  mixdown -18, ebu r128 -23, atsc a/85 -24, film -27, loud master -9 - each a
  target LUFS and a true peak ceiling; **save** keeps your own (in
  `Insight.presetFile`), `...` copies and pastes every setting. Then **freeze**
  (hold the picture, the analysis goes on), **reset** (integrate again, drop
  every held maximum) and the **bus**: BusManager's names (followed when the bus
  moves), a bus by number, how many channels.
- **spectrum**: log frequency, the loudest bin in each of 128 bands. Along its
  top: what it is of (sum, left, right, mid, side), the tilt, fast | medium |
  slow, max (the held trace) and the fft size. Drag up and down to move the floor
  of the range (shift: fine), scroll in 6 dB steps, double click for -96; a click
  clears the held trace. Hover for the frequency, the note as Tidal writes it
  (`a4`), the region and the level.
- **levels · loudness**: a bar per channel in dBFS - rms solid, the true peak
  pale above it, the highest since reset a white line and a number on top, red
  past the ceiling - and M and S, momentary and short term loudness in LUFS (the
  same blue, paler), with the target across them as an outlined band. Drag on the
  channels to move the ceiling, on M and S to move the target; a click drops
  the held peaks; double click: -1 dBTP, -14 LUFS.
- **loudness · short term**: the last minute (or five) of short term loudness,
  the target as an outlined band (±1 LU, `Insight.tolerance`), the integrated
  loudness dotted. Drag up and down: the target.
- **stereo**: the goniometer - mid up, side across, the hard-left and hard-right
  diagonals dashed, clipped samples red - with the correlation as a bar from 0
  (the half under 0, where the channels cancel, outlined), the balance under it
  and the width.
- **since reset**: the integrated loudness big, outlined while it is on the
  target, and how far from it in words; short term, range (LRA), peak to
  loudness ratio, true peak, sample peak and overs; the **target** and
  **ceiling** knobs (drag, shift fine, double click the default, click the value
  to type one, right click for the targets).
- The **hint** along the bottom says what is under the pointer and what it
  measures; the footer's right end says what it reads and whether it runs.

Colour means one thing each: signal blue for levels and the spectrum, the same
blue paler for loudness, white for what is held, outlines for the targets, red
only for an over.

## Integration

### With BusManager

Covered above: the bus can be a name, and the insight follows it. BusManager is
looked up at runtime, so nothing here needs it to compile.

### With ServerPanel

If [ServerPanel](https://github.com/scztt/ServerPanel.quark) is installed, its
meter/scope section grows a small **i** button, and its menu and keyboard both
gain an **Insight** action (the `i` key): either opens an insight on the main
out of that server, bus 0. The button only exists when the `Insight` class does,
so ServerPanel compiles and runs exactly as before without this extension.

The button is added in `ServerPanel.sc`, in `ScopeWidget:view`, behind a
`\Insight.asClass.notNil` check - a runtime lookup, never a compile-time
reference.

## Server restarts

The analysis registers with `ServerBoot`, `ServerTree` and `ServerQuit`:

- a **boot** takes every node and buffer with it; the analysis synth and its
  buffers are made again on the tree hook that follows, and an insight following
  a name looks its bus up again;
- **cmd + period** takes every node, so the synth is rebuilt after it;
- when the **server quits** the picture goes quiet and the window says so.

## What it is, and is not

An honest analyser, not a certified meter. The loudness follows BS.1770 and EBU
R128 - K-weighting, the 400 ms and 3 s windows, the two-stage gate for the
integrated value, the percentile range - and reads within a fraction of a LU of
a reference on steady signal. The true peak is a four-times-oversampled
reconstruction, which catches inter-sample peaks a sample meter misses. The
spectrum is a peak reading per band, log spaced, not a measuring instrument: it
is honest about where the energy is, which is what an eye on a mix wants.

## Files

| file | what it holds |
|------|---------------|
| [classes/Insight.sc](classes/Insight.sc) | the analyser: registry, bus resolution, the synth and its replies, settings, server lifecycle |
| [classes/InsightLoudness.sc](classes/InsightLoudness.sc) | the BS.1770 / R128 integrator: momentary, short term, integrated, range |
| [classes/InsightSpectrum.sc](classes/InsightSpectrum.sc) | one FFT frame folded into log spaced bands, with the falling traces |
| [classes/InsightGUI.sc](classes/InsightGUI.sc) | `InsightPanel` (the header and the panes), `InsightGUI` (the window), `InsightHintBar` (the old name of the hint strip) |
| [classes/InsightViews.sc](classes/InsightViews.sc) | the five panes: spectrum, meters, goniometer, history, readings |
| [HelpSource/Classes/Insight.schelp](HelpSource/Classes/Insight.schelp) | the help file, cmd/ctrl + d on the class name |
| [examples/insight-demo.scd](examples/insight-demo.scd) | a walkthrough you can evaluate block by block |
| [install.scd](install.scd) | symlinks the repository into the extensions folder |
| [sc-insight.quark](sc-insight.quark) | the quark manifest |

## Issues

Bug reports and questions are welcome in
[Issues](https://github.com/fracnesco/supercollider-insight-meter/issues).

## License

GPL-3.0-or-later - see [LICENSE](LICENSE).
