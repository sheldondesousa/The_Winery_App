# Gemma card timing diagnostics

Debug builds save one metadata-only record per Gemma card request to the app's
private `files/diagnostics/gemma-timings.log`, and to Logcat under `UncorkFlow`.
The file rotates at 256 KiB, retaining one previous file. Release builds do not
save these records. Records contain no prompts, preferences, or generated text.

Each record has a request ID and start time in epoch milliseconds. Durations
use a monotonic clock and are milliseconds:

| Field | Meaning |
| --- | --- |
| `queue_wait_ms` | Waiting for exclusive access to Gemma |
| `engine_ready_ms` | Get the existing engine, or initialize it if needed |
| `load_prompt_ms` | Get the card instructions, including first-use asset loading |
| `prepare_preferences_ms` | Serialize preferences for the model |
| `create_conversation_ms` | Create the short-lived card conversation |
| `send_to_first_output_ms` | Submit the prompt until the first nonempty text chunk; includes prompt processing and runtime overhead, not a pure prefill measurement |
| `first_to_last_output_ms` | Time between first and last nonempty text chunks |
| `last_output_to_stream_complete_ms` | Wait after the last text chunk until the stream completes |
| `send_to_stream_complete_ms` | Entire streaming operation; overlaps the preceding three fields |
| `close_conversation_ms` | Native conversation cleanup |
| `parse_response_ms` | Parse the response into wine cards |
| `validate_cards_ms` | Finalize the list of already validated cards |
| `incremental_parse_and_publish_ms` | Cumulative object detection, parsing, preference validation and publication during streaming; overlaps streaming duration |
| `card_1_ready_ms`, `card_2_ready_ms`, `card_3_ready_ms` | Elapsed time from card search start until each accepted card is published |
| `card_search_total_ms` | Entire card operation, excluding queue wait and writing the diagnostic file |
| `queue_wait_and_work_ms` | Queue wait plus the card operation |

Totals overlap component timings and must not be added to them. Small differences
include bookkeeping and response construction. UI rendering is outside these timers.

Additional fields record GPU/CPU backend, engine reuse, input/output character
counts, output chunk count, the configured output token limit, and parsed/usable
card counts. Chunks and characters are **not** token counts. These records cannot
prove that the model hit its token limit. A failure records its exception class;
unfinished stages may be absent, while entered timed stages record elapsed time
even on failure or cancellation.

Rejected complete objects add a privacy-safe field such as
`candidate_1_rejected=country_mismatch,invalid_type`. The reason codes never
contain generated values. `complete_objects` counts finished streamed JSON
objects, `rejected_cards` counts rejected complete objects, and
`missing_complete_objects` counts how many of the requested three never became
a complete streamed object. Possible codes include preference-field mismatch,
invalid or unknown required fields, invalid name forms, duplicate cards, JSON
parse failure, and extra objects after three accepted cards.
`recovered_final_cards` counts valid cards missed by progressive extraction but
recovered from the complete response before the final result is shown. Rejected
recovery candidates use keys such as `final_candidate_1_rejected`.
`stream_parser_start` reports whether progressive parsing found the requested
marker or a markerless raw array. `stream_parser_final_depth` is nonzero when
generation ended inside an unfinished object.

Read saved records without changing app data:

```sh
adb shell run-as com.sheldondesousa.uncork cat files/diagnostics/gemma-timings.log
adb shell run-as com.sheldondesousa.uncork cat files/diagnostics/gemma-timings.previous.log
```

The previous file exists only after rotation. A build containing this instrumentation
must be installed and a new Chat card search completed before records are available.
Existing historical runs cannot be reconstructed from these new checks.
