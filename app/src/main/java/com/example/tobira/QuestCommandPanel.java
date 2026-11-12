package com.example.tobira;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Drives the quest screen's "Enter Your Command" boxes (design: tobira_UI.pptx slide 2).
 * The player performs the quest's car commands in order; when the car reports an operation,
 * that box fills with the theme colour and shows a check mark, and the next box becomes active.
 * A box is only checked after the player operates the car while it is active: a state that is
 * already true (e.g. the parking brake was already on) must first change, then be set again.
 *
 * <p>Values come from {@link VehicleFlagManager}. Values this car or this app cannot read
 * (most HVAC, light and seat properties need system permissions) are not detected; a long press
 * on the active box completes it as a hidden operator override for demos.
 * Commands only advance while the car is stopped.
 */
final class QuestCommandPanel {

    /** Called once when every command of the current quest is done. */
    interface Listener {
        void onAllCommandsDone();
    }

    private static final long TICK_MS = 300;
    private static final float STOPPED_KMH = 3f;
    private static final String DONE_MARK = "✓";
    private static final int[] CELL_IDS = {
            R.id.command_cell_1, R.id.command_cell_2, R.id.command_cell_3, R.id.command_cell_4
    };

    private final Activity activity;
    private final Listener listener;
    private final TextView instruction;
    private final View[] cells = new View[CELL_IDS.length];
    private final TextView[] boxes = new TextView[CELL_IDS.length];
    private final VehicleFlagManager car;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<QuestCommand> commands = Collections.emptyList();
    private String source;
    private int index;
    private boolean sawUnmet;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            evaluate();
            if (running) handler.postDelayed(this, TICK_MS);
        }
    };

    QuestCommandPanel(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        instruction = activity.findViewById(R.id.command_instruction);
        for (int cell = 0; cell < CELL_IDS.length; cell++) {
            cells[cell] = activity.findViewById(CELL_IDS[cell]);
            boxes[cell] = cells[cell].findViewById(R.id.command_box);
            int position = cell;
            boxes[cell].setOnLongClickListener(ignored -> {
                if (position != index || index >= commands.size() || isMoving()) return false;
                advance();
                return true;
            });
        }
        car = new VehicleFlagManager(activity);
        car.start();
        render();
    }

    /** Shows the commands from a challenge's conditionJson; null or no commands clears the panel. */
    void setCommands(@Nullable String conditionJson) {
        if (Objects.equals(conditionJson, source)) return;
        source = conditionJson;
        commands = QuestCommand.parse(conditionJson);
        index = 0;
        sawUnmet = false;
        clearCurrentFlag();
        render();
    }

    /** Forget an operation done before the current box became active, so only a new one counts. */
    private void clearCurrentFlag() {
        if (index < commands.size() && commands.get(index).flag != null) {
            car.consumeFlag(commands.get(index).flag);
        }
    }

    void resume() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    void pause() {
        running = false;
        handler.removeCallbacks(tick);
    }

    void destroy() {
        pause();
        car.stop();
    }

    private boolean isMoving() {
        return car.getSpeedKmh() > STOPPED_KMH;
    }

    private void evaluate() {
        if (index >= commands.size()) return;
        if (isMoving()) {
            // Operations while driving (e.g. turn signals) must not count once the car stops.
            clearCurrentFlag();
            setInstruction(activity.getString(R.string.command_stop_first));
            return;
        }
        QuestCommand command = commands.get(index);
        if (command.kind == QuestCommand.Kind.FLAG) {
            if (command.flag != null && car.consumeFlag(command.flag)) advance();
            return;
        }
        Boolean met = command.isMet(car);
        if (Boolean.FALSE.equals(met)) {
            sawUnmet = true;
        } else if (Boolean.TRUE.equals(met) && sawUnmet) {
            advance();
            return;
        }
        render();
    }

    private void advance() {
        index++;
        sawUnmet = false;
        clearCurrentFlag();
        render();
        if (index == commands.size() && listener != null) listener.onAllCommandsDone();
    }

    private void render() {
        for (int cell = 0; cell < cells.length; cell++) {
            boolean used = cell < commands.size();
            cells[cell].setVisibility(used || commands.isEmpty() ? View.VISIBLE : View.INVISIBLE);
            TextView box = boxes[cell];
            if (!used) {
                box.setBackgroundResource(R.drawable.bg_cmd_box_empty);
                box.setText("");
                continue;
            }
            if (cell < index) {
                box.setBackgroundResource(R.drawable.bg_cmd_box_done);
                box.setText(DONE_MARK);
            } else {
                box.setBackgroundResource(cell == index
                        ? R.drawable.bg_cmd_box_active : R.drawable.bg_cmd_box_empty);
                box.setText("");
            }
        }
        if (commands.isEmpty()) {
            setInstruction("");
        } else if (index >= commands.size()) {
            setInstruction(activity.getString(R.string.command_all_clear));
        } else {
            setInstruction(activity.getString(R.string.command_step, index + 1,
                    commands.get(index).label));
        }
    }

    private void setInstruction(String text) {
        if (!text.contentEquals(instruction.getText())) instruction.setText(text);
    }
}
