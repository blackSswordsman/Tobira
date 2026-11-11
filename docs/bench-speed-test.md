# Debug bench speed evaluation inside place quests

Entry: start the quest route → start trip → open quest panel → confirm return to car.
The existing place task dialog contains a debug-only speed evaluation panel.
There is no separate test Activity, main-screen button or standalone speed quest.

Use only the bench, not a drive in a real vehicle. Speed is a temporary debug answer
condition for the opened task; the original story/task text remains visible above it.
Release retains its regular task preview without speed evaluation or Car speed permission.

1. Open a place's first unfinished challenge using 車に戻った・試練を開く.
2. Press ベンチの試練を開始 and grant vehicle speed access.
3. Send fresh speed outside 9–11 km/h, then change the bench to 10 km/h.
4. Hold 9–11 km/h for at least 2 seconds with continuous fresh samples.
5. Success atomically completes the opened quest ID/version/step in Room. If all of that
   place's challenges are complete, the place quest becomes COMPLETED.
6. Closing and reopening selects the next unfinished task. Locked finales stay locked.
   Bench completion DOES contribute to this debug playthrough's route progress.

The source reads PERF_VEHICLE_SPEED_DISPLAY (0x11600208), like bench-test:
https://developer.android.com/reference/android/car/VehiclePropertyIds#PERF_VEHICLE_SPEED_DISPLAY

Values are m/s, converted to km/h using ×3.6. Reverse speed does not satisfy the positive
target. Pre-start, invalid, old (>1 s), future, duplicate and out-of-order samples cannot
advance the timer. Lost data or closing the dialog resets partial hold time and requires
a new speed change. A value already in range on entry does not immediately count.

The asynchronous Car lifecycle API manages the connection. Property queries/subscriptions
run on a dedicated worker. Callbacks are detached when the dialog stops. Unsupported
properties, denied permission and disconnects do not become zero speed or success.
https://developer.android.com/reference/android/car/Car

SpeedHoldConditionTest covers timing and invalid/stale samples. IzumoQuestCatalogTest
covers atomic, idempotent task completion and its contribution to route progress.
