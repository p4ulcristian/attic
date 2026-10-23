# The app passes its log and its meter

**Decision:** `make-attic` takes the app's log (logbook's) and meter (meter's) under `:log` and `:meter`, required, and declares attic's metrics there, where it starts: `attic_files_stored_total{outcome}` (`stored`, `already-stored`), `attic_files_refused_total{reason}` (`wrong-kind`, `too-large`) and `attic_bucket_failures_total{operation}` (`store`, `read`), each label's set closed. attic writes one line itself, `:error :attic/upload-failed` with the exception, when an upload's store fails; every other failure is thrown to the caller, who decides. A log logbook didn't make, or a meter meter didn't make, is refused at `make-attic`. Since v0.1.0.

**Why:** as every manor library ([meter's decision](https://github.com/p4ulcristian/meter/blob/main/decisions/2026-10-23-a-library-takes-the-meter-as-meter.md)): the app decides how lines are written and serves the metrics. Stored against already stored says how much the content naming saves; refusals by reason show a browser sending the wrong files; bucket failures, a bucket in trouble.

**Instead of:**
- a histogram of the bucket's times: one more bucket setting, for what the bucket's own dashboard shows
- logging every store: a line per file for what the counter says
