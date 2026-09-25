# Gemma Chat cards without Summary — 25 September 2026

Summary was removed only from chat_search_instruction.txt. Flavour notes and other fields remain requested. Tested through normal app UI after adb install -r, preserving model and app data.

1. No preferences: no preference for Q1, Q2, Q3. CPU.
2. Red, France, no taste preference. CPU.
3. Red, France, full bodied. GPU after app restarted between tests.

All three engines were already loaded at search start. Each run produced three usable cards. The historical baseline used GPU for all three scenarios. The mixed-backend average below must not be attributed solely to removing Summary. One run per scenario; thermal conditions and sampled outputs were not controlled.

| Stage (milliseconds) | Test 1 | Test 2 | Test 3 | Average |
| --- | ---: | ---: | ---: | ---: |
| queue_wait_ms | 0 | 0 | 0 | 0.00 |
| engine_ready_ms | 0 | 0 | 0 | 0.00 |
| load_prompt_ms | 0 | 1 | 1 | 0.67 |
| prepare_preferences_ms | 0 | 0 | 0 | 0.00 |
| create_conversation_ms | 5 | 2 | 5 | 4.00 |
| send_to_first_output_ms | 3512 | 2161 | 1497 | 2390.00 |
| first_to_last_output_ms | 19476 | 20914 | 29700 | 23363.33 |
| last_output_to_stream_complete_ms | 7 | 1 | 2 | 3.33 |
| close_conversation_ms | 0 | 0 | 0 | 0.00 |
| parse_response_ms | 5 | 1 | 1 | 2.33 |
| validate_cards_ms | 0 | 0 | 1 | 0.33 |
| incremental_parse_and_publish_ms | 15 | 12 | 23 | 16.67 |
| card_search_total_ms | 23005 | 23081 | 31208 | 25764.67 |
| card_1_ready_ms | 10282 | 8712 | 11658 | 10217.33 |
| card_2_ready_ms | 16289 | 15595 | 21872 | 17918.67 |
| card_3_ready_ms | 22456 | 22552 | 30415 | 25141.00 |

Previous totals: 49.663s, 51.316s, 43.418s; average 48.132s. New totals: 23.005s, 23.081s, 31.208s; average 25.765s.

The same-backend third scenario improved from 43.418s to 31.208s (28.1% shorter), but this is a single-run comparison, not proof of a stable gain.

Incremental parsing/publication is included in streaming time; card-ready times are cumulative from search start. The final parser reported one card in test 2, while the incremental parser accepted and published three; the usable count is three. This discrepancy should be investigated separately.

## Raw records

```text
GemmaTiming request=4805c9d4-12cf-496f-9fde-dcd23b520f41 startedAtEpochMs=1790344548554 queue_wait_ms=0 engine_reused=true engine_ready_ms=0 backend=CPU load_prompt_ms=0 prepare_preferences_ms=0 input_characters=2019 max_output_tokens=1024 create_conversation_ms=5 send_to_first_output_ms=3512 card_1_ready_ms=10282 card_2_ready_ms=16289 card_3_ready_ms=22456 last_output_to_stream_complete_ms=7 send_to_stream_complete_ms=22995 incremental_parse_and_publish_ms=15 first_to_last_output_ms=19476 output_characters=1095 output_chunks=369 close_conversation_ms=0 parse_response_ms=5 validate_cards_ms=0 parsed_cards=3 usable_cards=3 card_search_total_ms=23005 queue_wait_and_work_ms=23005
GemmaTiming request=becd4051-c8fa-4f59-8824-0d5e7ea3553e startedAtEpochMs=1790344652069 queue_wait_ms=0 engine_reused=true engine_ready_ms=0 backend=CPU load_prompt_ms=1 prepare_preferences_ms=0 input_characters=2014 max_output_tokens=1024 create_conversation_ms=2 send_to_first_output_ms=2161 card_1_ready_ms=8712 card_2_ready_ms=15595 card_3_ready_ms=22552 last_output_to_stream_complete_ms=1 send_to_stream_complete_ms=23076 incremental_parse_and_publish_ms=12 first_to_last_output_ms=20914 output_characters=1161 output_chunks=380 close_conversation_ms=0 parse_response_ms=1 validate_cards_ms=0 parsed_cards=1 usable_cards=3 card_search_total_ms=23081 queue_wait_and_work_ms=23081
GemmaTiming request=ea3a4778-6033-4153-a3b8-2a73de745e77 startedAtEpochMs=1790344804167 queue_wait_ms=0 engine_reused=true engine_ready_ms=0 backend=GPU load_prompt_ms=1 prepare_preferences_ms=0 input_characters=2018 max_output_tokens=1024 create_conversation_ms=5 send_to_first_output_ms=1497 card_1_ready_ms=11658 card_2_ready_ms=21872 card_3_ready_ms=30415 last_output_to_stream_complete_ms=2 send_to_stream_complete_ms=31199 incremental_parse_and_publish_ms=23 first_to_last_output_ms=29700 output_characters=1110 output_chunks=365 close_conversation_ms=0 parse_response_ms=1 validate_cards_ms=1 parsed_cards=3 usable_cards=3 card_search_total_ms=31208 queue_wait_and_work_ms=31208
```
