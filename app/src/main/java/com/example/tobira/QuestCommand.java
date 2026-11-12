package com.example.tobira;

import android.car.VehiclePropertyIds;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One step of the "Enter Your Command" panel: a car operation the player performs while parked.
 * Parsed from a challenge step's conditionJson, e.g.
 * {"commands":[{"kind":"FLAG","flag":"TURN_LEFT_ON","label":"左の風を呼べ：左ウインカー"}]}
 *
 * <p>{@link Kind#FLAG} is the preferred kind: it succeeds when the car reports that operation as a
 * change ({@link VehicleFlag}, e.g. TURN_LEFT_ON, SEAT_OCCUPIED, PARKING_BRAKE_ON), which works with
 * the properties the real car actually provides.
 */
public final class QuestCommand {

    public enum Kind {
        /** The car raised {@link #flag} (a value changed by the player's operation). */
        FLAG,
        /** Parking brake applied ("on": true) or released. */
        PARKING_BRAKE,
        /** Fan speed at least {@code value}. */
        FAN_SPEED,
        /** Hazard lights on ("on": true) or off. */
        HAZARD,
        /** Steering wheel turned left by at least {@code value} degrees. */
        STEER_LEFT,
        /** Steering wheel turned right by at least {@code value} degrees. */
        STEER_RIGHT,
        /** At least {@code value} seats occupied. */
        SEATS_OCCUPIED
    }

    private static final int HAZARD_ON = 1;

    public final Kind kind;
    /** For {@link Kind#FLAG}: the operation to wait for. */
    @Nullable public final VehicleFlag flag;
    public final String label;
    public final float value;
    public final boolean on;
    /**
     * When true the condition must first be false after this step becomes active, so the player has
     * to actually operate the car (an already-on hazard does not count).
     */
    public final boolean change;

    private QuestCommand(Kind kind, @Nullable VehicleFlag flag, String label, float value, boolean on,
                         boolean change) {
        this.kind = kind;
        this.flag = flag;
        this.label = label;
        this.value = value;
        this.on = on;
        this.change = change;
    }

    /** Commands in {@code conditionJson}, or an empty list if it has none or cannot be parsed. */
    public static List<QuestCommand> parse(@Nullable String conditionJson) {
        if (conditionJson == null || conditionJson.isEmpty()) return Collections.emptyList();
        try {
            JSONArray array = new JSONObject(conditionJson).optJSONArray("commands");
            if (array == null) return Collections.emptyList();
            List<QuestCommand> commands = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                Kind kind = Kind.valueOf(item.getString("kind"));
                VehicleFlag flag = kind == Kind.FLAG ? VehicleFlag.valueOf(item.getString("flag")) : null;
                commands.add(new QuestCommand(kind, flag,
                        item.optString("label", ""), (float) item.optDouble("value", 0),
                        item.optBoolean("on", true), item.optBoolean("change", false)));
            }
            return commands;
        } catch (JSONException | IllegalArgumentException error) {
            return Collections.emptyList();
        }
    }

    /**
     * Whether the car currently satisfies this command, or null when this car (or this app's
     * permissions) does not provide the value.
     */
    @Nullable
    public Boolean isMet(VehicleFlagManager car) {
        switch (kind) {
            case FLAG:
                return null; // Event-based: handled by QuestCommandPanel with VehicleFlagManager flags.
            case PARKING_BRAKE: {
                Object brake = readable(car, VehiclePropertyIds.PARKING_BRAKE_ON);
                return brake instanceof Boolean ? (Boolean) brake == on : null;
            }
            case FAN_SPEED: {
                Object fan = readable(car, VehiclePropertyIds.HVAC_FAN_SPEED);
                return fan instanceof Integer ? (Integer) fan >= value : null;
            }
            case HAZARD: {
                Object hazard = readable(car, VehiclePropertyIds.HAZARD_LIGHTS_STATE);
                if (hazard == null) hazard = readable(car, VehiclePropertyIds.HAZARD_LIGHTS_SWITCH);
                return hazard instanceof Integer ? ((Integer) hazard == HAZARD_ON) == on : null;
            }
            case STEER_LEFT:
            case STEER_RIGHT: {
                // Left is negative (same convention as VehicleFlagManager.handleSteering).
                Object angle = readable(car, VehiclePropertyIds.PERF_STEERING_ANGLE);
                if (!(angle instanceof Float)) return null;
                float degrees = (Float) angle;
                return kind == Kind.STEER_LEFT ? degrees <= -value : degrees >= value;
            }
            case SEATS_OCCUPIED: {
                int occupied = car.getOccupiedSeatCount();
                return occupied < 0 ? null : occupied >= value;
            }
            default:
                return null;
        }
    }

    @Nullable
    private static Object readable(VehicleFlagManager car, int propertyId) {
        VehicleFlagManager.PropertyStatus status = car.getPropertyStatus(propertyId);
        if (status != VehicleFlagManager.PropertyStatus.SUBSCRIBED
                && status != VehicleFlagManager.PropertyStatus.READ_ONCE) return null;
        return car.getValue(propertyId);
    }
}
