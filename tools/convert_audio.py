"""Converts the authored .wav clips in audio/ into the .ogg files Minecraft loads,
levelling them against each other on the way through.

Every family of clips is listed in FAMILIES below: the glob its masters are named
by, the sounds/ directory they land in, and how a master's name becomes a take's.
Adding a family is a line there and nothing else.

Minecraft's sound engine reads Ogg Vorbis and nothing else, and it only pans and
attenuates a sound that is mono -- a stereo clip plays flat, at full volume,
everywhere in the world, which is not what a breaker on a wall should do. So each
clip is downmixed to one channel and encoded to Vorbis.

The takes were not recorded at matched levels: as authored the trips run some 9 dB
hotter than the closes, and one of them, trip_1, sits 19 dB below its own siblings.
Since each sound event picks a take at random, that is heard as the breaker being
randomly loud or faint rather than as five recordings of it, so every clip is
brought to one loudness here. That leaves how loud a close is against a trip to be
decided once, in CircuitBreakerControl, instead of falling out of which take came
up.

Every family is brought to that same one loudness, not just every take within a
family, so that how loud a switch is against a breaker is also decided in code --
in the volume each caller passes to playSound -- rather than in the recordings.

The .wav files are the masters and are the ones to re-record or replace; the .ogg
files under src/main/resources are build output, and are rewritten wholesale every
run.
Run it from anywhere:  python tools/convert_audio.py

Needs soundfile (pip install soundfile). It writes only under src/main/resources
and takes no part in the Gradle build."""
import pathlib
import numpy as np
import soundfile as sf

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "audio"
OUT = ROOT / "src" / "main" / "resources" / "assets" / "create_flippies" / "sounds"

# One entry per family of clips: the glob that finds its masters under audio/, the
# directory under sounds/ they are written to, and the prefix that is stripped off
# a master's name. The directory already says which family a take belongs to, so
# the part of the name that repeats it is dropped: mcb_close_1.wav becomes
# circuit_breaker/close_1.ogg. What is left is the take, and sounds.json is what
# groups takes into an event -- see ModSounds.
FAMILIES = (
    ("mcb_*.wav", "circuit_breaker", "mcb_", ""),
    # switch-a.wav -> rotary_switch/turn_a.ogg. The masters are lettered rather
    # than numbered, so the letter is kept as the take and "turn" says which of a
    # switch's noises it is, leaving room for others later.
    ("switch-*.wav", "rotary_switch", "switch-", "turn_"),
)

# The window a clip's loudness is measured over. These are clacks -- a few
# milliseconds of transient over a much quieter body -- so peak level says almost
# nothing about how loud one sounds: the closes peak within a decibel of the trips
# while sounding far softer, because the peak is all they have. 30 ms is roughly
# what the ear integrates a click over, and it ranks these the way listening to
# them does.
WINDOW = 0.030

# What every clip is brought to, as dBFS in that window. How high this can go is
# set by the closes: they are the spikiest of the ten, so they are the ones the
# limiter has to work on to get here, and much past this it starts taking the snap
# off them. Raising it makes everything louder in game and blunter; lowering it
# does the reverse.
TARGET_DB = -13.0

# Where the limiter's ceiling sits. Well under full scale on purpose: Vorbis is
# not sample-accurate and rings two to three decibels past a transient this sharp,
# and Minecraft decodes to 16-bit before it touches the volume, so an overshoot is
# a clipped sample however quietly the game ends up playing it. The level of the
# result does not depend on this -- the gain loop below makes up whatever the
# ceiling costs -- so it can be set wherever the encoder stops overshooting.
CEILING = 0.70

# Where the limiter starts working, as a fraction of the ceiling. Everything below
# is passed through untouched, so only the top of a transient is ever bent.
KNEE = 0.5

# The most a decoded clip may peak at, checked against the file that was written
# rather than the samples that went into it.
DECODED_CEILING = 0.99


def loudness(mono: np.ndarray, rate: int) -> float:
    """The clip's level: the loudest {@link WINDOW} of it, as RMS."""
    width = max(1, min(int(WINDOW * rate), len(mono)))
    energy = np.convolve(mono * mono, np.ones(width), "valid")
    return float(np.sqrt(energy.max() / width))


def soft_limit(x: np.ndarray, ceiling: float) -> np.ndarray:
    """
    Folds whatever is over the knee down under the ceiling along a tanh, rather
    than flattening it against a hard threshold. Nothing here is ever driven far
    enough in for that curve to be heard as anything but a slightly softer tip on
    the transient.
    """
    knee = KNEE * ceiling
    over = np.abs(x) > knee
    out = x.copy()
    out[over] = np.sign(x[over]) * (knee + (ceiling - knee)
                                    * np.tanh((np.abs(x[over]) - knee) / (ceiling - knee)))
    return out


def encode(mono: np.ndarray, rate: int, target: float, ogg: pathlib.Path):
    """
    Writes the clip at the target loudness, measuring what came back off the
    encoder rather than what went into it.

    The gain cannot be worked out in one go, because the limiter takes some of it
    back and how much depends on the shape of the clip -- a spiky close loses six
    decibels to it where a dense trip loses a quarter of one. So it is asked for,
    measured, and asked for again until the file on disk is where it should be.
    """
    ceiling = CEILING
    gain = target / loudness(mono, rate)
    for _ in range(16):
        sf.write(ogg, soft_limit(mono * gain, ceiling), rate, format="OGG", subtype="VORBIS")
        decoded, _ = sf.read(ogg, dtype="float32")
        peak = float(np.abs(decoded).max())
        got = loudness(decoded, rate)
        if peak > DECODED_CEILING:
            ceiling *= 0.85
            continue
        if abs(20 * np.log10(got / target)) < 0.1:
            break
        gain *= target / got
    return gain, got, peak


def main() -> None:
    target = 10 ** (TARGET_DB / 20)
    written = 0

    for pattern, directory, strip, prefix in FAMILIES:
        wavs = sorted(SRC.glob(pattern))
        if not wavs:
            print(f"{directory}: no {pattern} under {SRC}, skipped")
            continue

        out = OUT / directory
        out.mkdir(parents=True, exist_ok=True)
        print(f"{directory}/")
        for wav in wavs:
            samples, rate = sf.read(wav, always_2d=True, dtype="float32")
            # Average rather than sum: a sum of two correlated channels clips a clip
            # that was mastered anywhere near full scale, and every one of these is
            mono = samples.mean(axis=1)
            ogg = out / (prefix + wav.stem[len(strip):] + ".ogg")
            gain, got, peak = encode(mono, rate, target, ogg)
            written += 1

            linear = 20 * np.log10(target / loudness(mono, rate))
            print(f"  {ogg.stem:<8} {20 * np.log10(gain):+6.2f} dB "
                  f"({linear:+6.2f} level, {20 * np.log10(gain) - linear:4.2f} to the limiter)"
                  f"  ->  {20 * np.log10(got):6.2f} dBFS, peak {peak:.3f}"
                  f"  ({ogg.stat().st_size / 1024:.1f} KiB)")

    if not written:
        raise SystemExit(f"no clips matched any family under {SRC}")


if __name__ == "__main__":
    main()
