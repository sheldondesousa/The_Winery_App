# Gemma timing comparison — 25 September 2026

Three normal Chat searches on the Pixel 10 Pro Fold, using the installed progressive-card build. All used an already loaded GPU engine. One run per scenario, not three repetitions per scenario.

1. No preferences: Q1, Q2 and Q3 answered "no preference".
2. Q1: red; Q2: France; Q3: no preference.
3. Q1: red; Q2: France; Q3: full bodied.

| Stage (milliseconds) | Test 1 | Test 2 | Test 3 | Average |
| --- | ---: | ---: | ---: | ---: |
| queue_wait_ms | 0 | 0 | 0 | 0.00 |
| engine_ready_ms | 0 | 0 | 0 | 0.00 |
| load_prompt_ms | 4 | 0 | 3 | 2.33 |
| prepare_preferences_ms | 0 | 0 | 0 | 0.00 |
| create_conversation_ms | 20 | 32 | 31 | 27.67 |
| send_to_first_output_ms | 2112 | 1894 | 2314 | 2106.67 |
| first_to_last_output_ms | 47492 | 49379 | 41050 | 45973.67 |
| last_output_to_stream_complete_ms | 17 | 9 | 11 | 12.33 |
| close_conversation_ms | 3 | 1 | 3 | 2.33 |
| parse_response_ms | 13 | 1 | 6 | 6.67 |
| validate_cards_ms | 0 | 0 | 0 | 0.00 |
| incremental_parse_and_publish_ms | 51 | 41 | 49 | 47.00 |
| card_search_total_ms | 49663 | 51316 | 43418 | 48132.33 |
| card_1_ready_ms | Not produced | 18224 | 16108 | Not available across all three |
| card_2_ready_ms | Not produced | 34162 | 29381 | Not available across all three |
| card_3_ready_ms | Not produced | 50336 | 42591 | Not available across all three |

Test 1 parsed two cards but accepted none. Its duration is included in process averages; absent card-ready times are not treated as zero. The available logs do not identify its rejection reasons. Tests 2 and 3 each accepted three cards.

Incremental parsing/publication is included in streaming time. Card-ready values are cumulative from search start, not additional stages. Screen navigation and entering answers are excluded. No thermal or GPU-clock controls were applied, so differences cannot be attributed solely to preferences.

## Source records

```text
GemmaTiming request=4e570126-fa9a-4ff9-a4f9-18693bed5009 startedAtEpochMs=1790341926605 queue_wait_ms=0 engine_reused=true engine_ready_ms=0 backend=GPU load_prompt_ms=4 prepare_preferences_ms=0 input_characters=2089 max_output_tokens=1024 create_conversation_ms=20 send_to_first_output_ms=2112 last_output_to_stream_complete_ms=17 send_to_stream_complete_ms=49622 incremental_parse_and_publish_ms=51 first_to_last_output_ms=47492 output_characters=1583 output_chunks=520 close_conversation_ms=3 parse_response_ms=13 validate_cards_ms=0 parsed_cards=2 usable_cards=0 card_search_total_ms=49663 queue_wait_and_work_ms=49663
GemmaTiming request=476e0e1a-65d4-477c-a664-44fb6b1b3423 startedAtEpochMs=1790342120068 queue_wait_ms=0 engine_reused=true engine_ready_ms=0 backend=GPU load_prompt_ms=0 prepare_preferences_ms=0 input_characters=2084 max_output_tokens=1024 create_conversation_ms=32 send_to_first_output_ms=1894 card_1_ready_ms=18224 card_2_ready_ms=34162 card_3_ready_ms=50336 last_output_to_stream_complete_ms=9 send_to_stream_complete_ms=51282 incremental_parse_and_publish_ms=41 first_to_last_output_ms=49379 output_characters=1666 output_chunks=546 close_conversation_ms=1 parse_response_ms=1 validate_cards_ms=0 parsed_cards=3 usable_cards=3 card_search_total_ms=51316 queue_wait_and_work_ms=51316
GemmaTiming request=a280b88d-d641-4e2a-878c-f1ded0cc3843 startedAtEpochMs=1790342590848 queue_wait_ms=0 engine_reused=true engine_ready_ms=0 backend=GPU load_prompt_ms=3 prepare_preferences_ms=0 input_characters=2088 max_output_tokens=1024 create_conversation_ms=31 send_to_first_output_ms=2314 card_1_ready_ms=16108 card_2_ready_ms=29381 card_3_ready_ms=42591 last_output_to_stream_complete_ms=11 send_to_stream_complete_ms=43375 incremental_parse_and_publish_ms=49 first_to_last_output_ms=41050 output_characters=1453 output_chunks=489 close_conversation_ms=3 parse_response_ms=6 validate_cards_ms=0 parsed_cards=3 usable_cards=3 card_search_total_ms=43418 queue_wait_and_work_ms=43418
```
