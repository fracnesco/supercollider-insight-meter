// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-insight.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// Insight depends on nothing. If BusManager happens to be installed as
// well, an insight can be pointed at a bus by name - Insight(\verb,
// \reverb) - and follows the name from then on; and if ServerView is
// installed, its meter section grows a button that opens the insight on
// the main out. Neither repository needs the other to compile.

(
var src = PathName(thisProcess.nowExecutingPath).pathOnly;
var dst = Platform.userExtensionDir +/+ "Insight";

// drop the trailing slash, ln -s does not like it
if (src.endsWith("/")) { src = src.drop(-1) };

case
{ File.exists(dst) } {
	("Insight: % already exists - remove it first if you want to relink."
		.format(dst)).warn;
}
{ true } {
	File.mkdir(Platform.userExtensionDir);
	("ln -s" + src.shellQuote + dst.shellQuote).unixCmd({ |exit|
		if (exit == 0) {
			"Insight: linked".postln;
			("   " ++ src).postln;
			("-> " ++ dst).postln;
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		} {
			("Insight: linking failed with exit code" + exit).warn;
		};
	});
};
)


// ---------------------------------------------------------------- verify
(
var missing = [\Insight, \InsightLoudness, \InsightSpectrum, \InsightGUI,
	\InsightSpectrumView, \InsightMeterView, \InsightImagerView, \InsightHistoryView,
	\InsightHintBar]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("Insight is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors.".format(missing.join(", "))).warn;
} {
	"Insight is installed.".postln;
	"  insights: % - %".format(Insight.size, Insight.names.join(", ")).postln;
	"  window:   Insight.open        - the main out, bus 0".postln;
	"            Insight(\\verb, 16).gui".postln;
	"  help:     Insight, then cmd+d".postln;
	if (\BusManager.asClass.notNil) {
		"  BusManager is installed too - a bus can be picked by name: Insight(\\verb, \\reverb)"
			.postln;
	};
	if (\ServerView.asClass.notNil) {
		"  ServerView is installed too - its meters have an i button that opens Insight"
			.postln;
	};
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "Insight";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
