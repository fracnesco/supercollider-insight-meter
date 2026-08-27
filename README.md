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

It depends on nothing - no plugins, no other quarks. If **BusManager** happens
to be installed alongside it, an insight can be pointed at a bus by name and
follows that name from then on. If **ServerView** is installed, its meter
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
Quarks.install("https://github.com/fracnesco/sc-insight.git");
thisProcess.recompile;
```

`Quarks.install` clones the repository into your `downloaded-quarks` folder and
puts it on the class path; the recompile is what makes the classes exist, and is
the same thing as `Language -> Recompile Class Library` (ctrl/cmd + shift + L).

Afterwards:

```supercollider
Quarks.update("sc-insight");       // pull the newest version, then recompile
Quarks.uninstall("sc-insight");    // take it off the class path
Quarks.gui;                        // see what is installed
```

The quark goes by the name of the repository, `sc-insight`; the class inside it
is `Insight`.

### From a clone

If you would rather have a working copy you can edit, clone it wherever you keep
things and link it into the extensions folder:

```sh
git clone https://github.com/fracnesco/sc-insight.git
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
```

`target` and `ceiling` are marks, not limits: nothing is changed on the bus, they
are only drawn, so you can see how far from -14 LUFS and -1 dBTP the signal is.

## The window

`Insight.at(\main).gui`, or `Insight.gui(\main)`.

```
 insight  main   bus [0] [out1_2 v] ch [2]                 [run] [freeze] [reset]
 spectrum  source [sum v] res [4096 v] tilt [3 dB/oct v] [fast v] [x] max hold   target [-14] LUFS  ceiling [-1] dBTP
 +--------------------------------------------------+  +--------------+
 |  spectrum                                        |  |  L  R   M S  |
 |                                                  |  |  |  |   | |  |
 |   20   50  100  200  500  1k   2k   5k  10k 20k  |  |  M  -18.3    |
 +--------------------------------------------------+  |  S  -17.9    |
 +----------------+ +-----------------------------+    |  I  -18.1    |
 |   goniometer   | |  short term loudness, 60 s  |    |  LRA  6.2    |
 |   corr / bal   | |                             |    |  TP  -0.8    |
 +----------------+ +-----------------------------+    +--------------+
 bus 0 · out1_2 · 2 ch · 48 kHz · fft 4096 · running · integrating 01:23
```

- The **bus** field is a number box and, with BusManager installed, a dropdown
  of its names beside it: pick a name and the insight follows it, type an index
  and it does not.
- **run** is the analysis synth, **freeze** holds the picture, **reset** starts
  the integration over and clears every held maximum.
- The **spectrum** panel: click it to clear the held trace, scroll it to change
  the range, hover it for the frequency, note and level under the mouse.
- The **meters** on the right: a bar per channel - rms filled, true peak a line,
  the highest held a mark, red past the ceiling - and two bars for momentary and
  short term loudness with the target across them, then the numbers.
- The **goniometer**: mid up, side across, so a mono signal is a vertical line
  and an out-of-phase one is horizontal, with correlation and balance below.
- The **history**: short term loudness over the last minute, with the target and
  the integrated value drawn across it.
- The **hint bar** along the bottom says what each control does and, for the meters, **exactly what each reading measures** - hover the `I` number for how integrated loudness is gated, `TP` for true peak, the goniometer for how to read phase. Untick *hints* to fold it away. This is the same hover-help the module windows have, written into Insight so it needs nothing else.

## Integration

### With BusManager

Covered above: the bus can be a name, and the insight follows it. BusManager is
looked up at runtime, so nothing here needs it to compile.

### With ServerView

If [ServerView](https://github.com/scztt/ServerView.quark) is installed, its
meter/scope section grows a small **i** button, and its menu and keyboard both
gain an **Insight** action (the `i` key): either opens an insight on the main
out of that server, bus 0. The button only exists when the `Insight` class does,
so ServerView compiles and runs exactly as before without this extension.

The button is added in `ServerView.sc`, in `ScopeWidget:view`, behind a
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
| [classes/InsightGUI.sc](classes/InsightGUI.sc) | the window, the controls along its top, and `InsightHintBar` - the hover-help strip |
| [classes/InsightViews.sc](classes/InsightViews.sc) | the four panels: spectrum, meters, goniometer, history |
| [HelpSource/Classes/Insight.schelp](HelpSource/Classes/Insight.schelp) | the help file, cmd/ctrl + d on the class name |
| [examples/insight-demo.scd](examples/insight-demo.scd) | a walkthrough you can evaluate block by block |
| [install.scd](install.scd) | symlinks the repository into the extensions folder |
| [sc-insight.quark](sc-insight.quark) | the quark manifest, named after the repository so `Quarks` can find it |

## Issues

Bug reports and questions are welcome in
[Issues](https://github.com/fracnesco/sc-insight/issues).

## License

GPL-3.0-or-later - see [LICENSE](LICENSE).
