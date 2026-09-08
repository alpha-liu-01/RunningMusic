# Recorded step fixtures

Real recordings. Runs 0 to 4 come from the sensor spike, captured on a Xiaomi
Redmi Note 12 5G running Android 16 (API 36) with the Qualcomm `step_detect`
sensor. Written by `StepRecorder`, read by `parseStepRecording`, and documented
in full by [SENSOR_SPIKE.md](../../../../../docs/private/SENSOR_SPIKE.md), whose
run numbering these filenames follow. Run 5 comes from a different phone
entirely and is here to prove that the first five were lucky.

They are checked in because a recording is the only honest input the control
loop has. Synthesising a step stream tests the loop against the gait we imagined
rather than the one the hardware reports, and the two differ in exactly the
places that matter: missed steps, doubled steps, detector warm-up, and delivery
arriving in bursts rather than one event at a time.

Cadence is given twice below, and the gap between the two columns is the reason
the control loop takes a median. The mean is step count divided by elapsed time,
so every step the detector missed is counted as time spent not stepping; the
median stride is what the legs were actually doing. On run 4 they differ by 10
spm, and a loop matching music to the mean would have run that much slow.

| File | Steps | Span | Mean | Median | What it is |
| --- | --- | --- | --- | --- | --- |
| `run0-nowake-short.txt` | 54 | 28 s | 115 spm | 115 spm | An aborted first attempt. Useful only as a short input. |
| `run1-nowake-tethered-walk.txt` | 196 | 92 s | 128 spm | 137 spm | Non-wakeup sensor, USB tethered, so the CPU never truly slept. |
| `run2-wake-untethered-walk.txt` | 211 | 118 s | 107 spm | 100 spm | Wakeup sensor, untethered, screen off. |
| `run3-wake-untethered-walk.txt` | 239 | 119 s | 120 spm | 125 spm | As run 2, repeated. |
| `run4-wake-untethered-run.txt` | 888 | 300 s | 177 spm | 187 spm | The one that settled the spike: CPU suspended 61% of the time, 888 of 917 counted steps recovered. |
| `run5-oplus-detector-ticks.txt` | 130 | 115 s | 68 spm | 60 spm | A OnePlus PKX110 walk. Neither number is the truth. |

Runs 0 to 3 were recorded at walking pace, which was a limitation at the time
and is now the point: they are real walking, so the loop's walking detection is
tested against a gait nobody had to guess at. Run 1 is the most valuable of
them, because at 137 spm it is a brisk enough walk to look like a slow jog, and
it is what set the loop's walking floor. Run 4 is the only one at running cadence
and is the fixture the measure-then-lock behaviour is pinned to.

Run 5 is the counter-example the other five could not supply, and it is why
cadence is now counted rather than timed. The OnePlus PKX110 declares its step
detector `SPECIAL_TRIGGER`, which means one event per step, and then emits on a
994.3 ms hardware tick regardless of what its owner is doing: 103 of the 129
gaps in this walk are the same 994.3 ms to within half a millisecond, and the
longest gap in the whole recording is one tick. The median stride therefore
measures the tick, and this walk, the two others recorded beside it, and a run
all came out at 60.3 spm. Nothing in the loop was wrong; its input was.

It is a version 2 recording, so it has no counter readings to fall back to and
no record of what the detector put in `values[0]`. Both are captured from
version 3 onward, which is what the format grew for.

Replay any of them with:

    ./gradlew :cadence:cadenceReplay --args="cadence/src/test/resources/fixtures --mode continuous"

Note that the sensor timestamps and the arrival timestamps sit on the same clock
base on this device, roughly two seconds apart. The loop does not assume that,
and a fixture from a device where the two disagree would be a valuable addition.
