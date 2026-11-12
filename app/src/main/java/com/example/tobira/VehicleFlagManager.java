package com.example.tobira;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads every app-readable property in the hackathon's "Accord VehicleProperty Support List",
 * keeps the latest value of each, and raises a {@link VehicleFlag} when an operation is detected.
 * ハッカソンの「Accord VehicleProperty Support List」でアプリが読めるプロパティをすべて取得し、
 * 最新値を保持して、操作を検知したら {@link VehicleFlag} を立てるクラス。
 *
 * <ul>
 *   <li>Fixed-value properties (car model, units…) are read once at start. / 固定値は起動時に1回だけ読む。</li>
 *   <li>Other properties are subscribed and updated whenever they change. / それ以外は購読して変化のたびに更新。</li>
 *   <li>Unsupported or no-permission properties are skipped and logged. / 非対応・権限なしはスキップしてログに出す。</li>
 * </ul>
 *
 * Usage / 使い方:
 * <pre>
 *   VehicleFlagManager vfm = new VehicleFlagManager(context);
 *   vfm.setFlagListener((flag, areaId, value) -> { ... });   // flags, push style / フラグ（コールバック）
 *   vfm.start();
 *   if (vfm.consumeFlag(VehicleFlag.HAZARD_ON)) { ... }      // flags, poll style / フラグ（ポーリング）
 *   float kmh = vfm.getSpeedKmh();                           // latest values / 最新値
 *   Object v  = vfm.getValue(VehiclePropertyIds.FUEL_LEVEL);
 *   vfm.logAllValues();                                      // dump everything to Logcat / 全値をログ出力
 *   vfm.stop();                                              // in onDestroy()
 * </pre>
 */
public class VehicleFlagManager {

    private static final String TAG = "VehicleFlagManager";

    /** Called on the main thread each time a flag is raised. / フラグが立つたびにメインスレッドで呼ばれる。 */
    public interface FlagListener {
        void onFlagRaised(VehicleFlag flag, int areaId, Object value);
    }

    // ---- Thresholds (tune for the game) / しきい値（ゲームに合わせて調整） ----
    private float overSpeedKmh = 60f;           // OVER_SPEED [km/h]
    private float overSpeedHysteresisKmh = 5f;  // この分下がったら OVER_SPEED を解除
    private float movingKmh = 1f;               // これを超えたら「走行中」
    private float suddenAccelMs2 = 3.0f;        // 急加速・急ブレーキ [m/s²]（約0.3G）
    private long suddenCooldownMs = 2000;       // 急加速・急ブレーキの連続発火を防ぐ間隔
    private float steerHardDeg = 90f;           // 大きく切った [deg]
    private float steerCenterDeg = 10f;         // 中央に戻った [deg]
    private float lowRangeKm = 50f;             // LOW_RANGE [km]
    private float freezingC = 3f;               // NEAR_FREEZING [°C]

    /**
     * Speed source for flags and getSpeedKmh(). The bench only provides PERF_VEHICLE_SPEED_DISPLAY.
     * 車速の取得元。ベンチ環境では PERF_VEHICLE_SPEED_DISPLAY を使う。
     * PERF_VEHICLE_SPEED is still read, but only as a value (no flags).
     */
    private static final int SPEED_PROPERTY = VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY;

    // ---- AOSP enum values (same as android.hardware.automotive.vehicle) / AOSP の列挙値 ----
    private static final int LIGHT_OFF = 0;               // VehicleLightState / VehicleLightSwitch .OFF
    private static final int LIGHT_ON = 1;                // .ON
    private static final int LIGHT_AUTOMATIC = 0x100;     // VehicleLightSwitch.AUTOMATIC (256)
    private static final int FAN_DIRECTION_DEFROST = 0x4; // VehicleHvacFanDirection.DEFROST
    private static final int TURN_NONE = 0;               // VehicleTurnSignal
    private static final int TURN_RIGHT = 1;
    private static final int TURN_LEFT = 2;
    private static final int GEAR_NEUTRAL = 0x0001;       // VehicleGear
    private static final int GEAR_REVERSE = 0x0002;
    private static final int GEAR_PARK = 0x0004;
    private static final int GEAR_DRIVE = 0x0008;
    private static final int IGNITION_LOCK = 1;           // VehicleIgnitionState
    private static final int IGNITION_OFF = 2;
    private static final int IGNITION_ACC = 3;
    private static final int IGNITION_ON = 4;
    private static final int IGNITION_START = 5;
    private static final int WINDOW_FRONT_WINDSHIELD = 0x0001; // VehicleAreaWindow
    private static final int WINDOW_REAR_WINDSHIELD = 0x0002;
    private static final int SEAT_VACANT = 1;             // VehicleSeatOccupancyState
    private static final int SEAT_OCCUPIED = 2;
    private static final int ETC_VALID = 1;               // ElectronicTollCollectionCardStatus
    private static final int ETC_INVALID = 2;
    private static final int ETC_NOT_INSERTED = 3;

    /**
     * Lights: {switch, state}. The switch (the driver's operation) is used first; the state only if the switch
     * is not available. Both are always read for values, but only one raises flags, so nothing fires twice.
     * ライト：{スイッチ, 状態}。フラグはスイッチ（運転手の操作）を優先し、使えない時だけ状態から立てる（二重発火防止）。
     */
    private static final int[][] LIGHT_PAIRS = {
            {VehiclePropertyIds.HEADLIGHTS_SWITCH, VehiclePropertyIds.HEADLIGHTS_STATE},
            {VehiclePropertyIds.HIGH_BEAM_LIGHTS_SWITCH, VehiclePropertyIds.HIGH_BEAM_LIGHTS_STATE},
            {VehiclePropertyIds.FOG_LIGHTS_SWITCH, VehiclePropertyIds.FOG_LIGHTS_STATE},
            {VehiclePropertyIds.HAZARD_LIGHTS_SWITCH, VehiclePropertyIds.HAZARD_LIGHTS_STATE},
    };

    /**
     * Every property in the support list that an app can read (62 of 76), plus 3 door properties
     * outside the list for a bench check (65 in total).
     * サポートリストのうちアプリが読めるプロパティすべて（76件中62件）＋ベンチ確認用のドア3件（計65件）。
     *
     * Not included / 含めないもの:
     *  - Access "–" in the list (9): INFO_VIN, NIGHT_MODE, AP_POWER_STATE_REQ, AP_POWER_STATE_REPORT,
     *    DISPLAY_BRIGHTNESS, HW_KEY_INPUT, POWER_POLICY_REQ, POWER_POLICY_GROUP_REQ, CURRENT_POWER_POLICY
     *  - System-internal (5): VEHICLE_MAP_SERVICE (deprecated), LOCATION_CHARACTERIZATION,
     *    SUPPORT_CUSTOMIZE_VENDOR_PERMISSION, DISABLED_OPTIONAL_FEATURES, HVAC_TEMPERATURE_VALUE_SUGGESTION
     */
    private static final int[] ALL_PROPERTIES = {
            // Vehicle info (fixed) / 車両情報（固定値）
            VehiclePropertyIds.INFO_MAKE,
            VehiclePropertyIds.INFO_MODEL,
            VehiclePropertyIds.INFO_MODEL_YEAR,
            VehiclePropertyIds.INFO_FUEL_CAPACITY,
            VehiclePropertyIds.INFO_FUEL_TYPE,
            VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY,
            VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION,
            VehiclePropertyIds.INFO_DRIVER_SEAT,
            VehiclePropertyIds.INFO_EXTERIOR_DIMENSIONS,
            VehiclePropertyIds.VEHICLE_CURB_WEIGHT,              // API 34+
            // Driving / 走行
            VehiclePropertyIds.PERF_ODOMETER,
            VehiclePropertyIds.PERF_VEHICLE_SPEED,
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY,
            VehiclePropertyIds.PERF_STEERING_ANGLE,
            VehiclePropertyIds.WHEEL_TICK,
            VehiclePropertyIds.GEAR_SELECTION,
            VehiclePropertyIds.PARKING_BRAKE_ON,
            VehiclePropertyIds.TURN_SIGNAL_STATE,
            VehiclePropertyIds.IGNITION_STATE,
            // Energy / エネルギー
            VehiclePropertyIds.FUEL_LEVEL,
            VehiclePropertyIds.FUEL_LEVEL_LOW,
            VehiclePropertyIds.EV_BATTERY_LEVEL,
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
            VehiclePropertyIds.RANGE_REMAINING,
            // Air conditioner / エアコン
            VehiclePropertyIds.HVAC_POWER_ON,
            VehiclePropertyIds.HVAC_FAN_SPEED,
            VehiclePropertyIds.HVAC_FAN_DIRECTION,
            VehiclePropertyIds.HVAC_FAN_DIRECTION_AVAILABLE,
            VehiclePropertyIds.HVAC_TEMPERATURE_CURRENT,
            VehiclePropertyIds.HVAC_TEMPERATURE_SET,
            VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS,
            VehiclePropertyIds.HVAC_DEFROSTER,
            VehiclePropertyIds.HVAC_ELECTRIC_DEFROSTER_ON,
            VehiclePropertyIds.HVAC_AC_ON,
            VehiclePropertyIds.HVAC_MAX_AC_ON,
            VehiclePropertyIds.HVAC_RECIRC_ON,
            VehiclePropertyIds.HVAC_DUAL_ON,
            VehiclePropertyIds.HVAC_AUTO_ON,
            VehiclePropertyIds.HVAC_SEAT_TEMPERATURE,
            VehiclePropertyIds.HVAC_SEAT_VENTILATION,
            VehiclePropertyIds.HVAC_STEERING_WHEEL_HEAT,
            // Environment / 環境
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE,
            // Display units (fixed) / 表示単位（固定値）
            VehiclePropertyIds.DISTANCE_DISPLAY_UNITS,
            VehiclePropertyIds.FUEL_VOLUME_DISPLAY_UNITS,
            VehiclePropertyIds.FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME,
            VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
            // Seats / シート
            VehiclePropertyIds.SEAT_BELT_BUCKLED,
            VehiclePropertyIds.SEAT_OCCUPANCY,
            // Windows / 窓（OTA A14）
            VehiclePropertyIds.WINDOW_POS,
            VehiclePropertyIds.WINDOW_LOCK,
            // Lights / ライト
            VehiclePropertyIds.HEADLIGHTS_SWITCH,
            VehiclePropertyIds.HEADLIGHTS_STATE,
            VehiclePropertyIds.HIGH_BEAM_LIGHTS_SWITCH,
            VehiclePropertyIds.HIGH_BEAM_LIGHTS_STATE,
            VehiclePropertyIds.FOG_LIGHTS_SWITCH,
            VehiclePropertyIds.FOG_LIGHTS_STATE,
            VehiclePropertyIds.HAZARD_LIGHTS_SWITCH,
            VehiclePropertyIds.HAZARD_LIGHTS_STATE,
            VehiclePropertyIds.CABIN_LIGHTS_SWITCH,
            VehiclePropertyIds.READING_LIGHTS_SWITCH,
            // ETC
            VehiclePropertyIds.ELECTRONIC_TOLL_COLLECTION_CARD_TYPE,
            VehiclePropertyIds.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS,
            // Doors (NOT in the support list, bench check only) / ドア（サポートリスト外・ベンチ確認用）
            // Needs CONTROL_CAR_DOORS (system permission), so likely "no permission" or "not supported".
            // Values only, no flags yet. / 値のみ取得（フラグなし）。
            VehiclePropertyIds.DOOR_POS,
            VehiclePropertyIds.DOOR_LOCK,
            VehiclePropertyIds.DOOR_CHILD_LOCK_ENABLED,           // API 34+
    };

    private final Context context;
    private Car car;
    private CarPropertyManager propertyManager;
    private FlagListener listener;

    private final Set<VehicleFlag> flags = ConcurrentHashMap.newKeySet();
    /** Latest value of every property, key = "propertyId:areaId". / 全プロパティの最新値。 */
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> beltBySeat = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> occupiedBySeat = new ConcurrentHashMap<>();
    private final List<Integer> supportedProperties = new ArrayList<>();
    /** Light state properties that must not raise flags because their switch is available. */
    private final Set<Integer> flagMutedProperties = ConcurrentHashMap.newKeySet();
    /** Why each property is (or is not) being read. / 各プロパティの取得状態。 */
    private final Map<Integer, PropertyStatus> statusByProperty = new ConcurrentHashMap<>();
    /** When each property last delivered a value (System.currentTimeMillis). / 最終更新時刻。 */
    private final Map<Integer, Long> lastUpdateMs = new ConcurrentHashMap<>();
    /** Area type (door, seat, window…) of each property, from its config. / エリア種別。 */
    private final Map<Integer, Integer> areaTypeByProperty = new ConcurrentHashMap<>();

    /** Result of trying to read a property. / プロパティの取得結果。 */
    public enum PropertyStatus {
        SUBSCRIBED,     // 監視中（変化のたびに更新）
        READ_ONCE,      // 固定値を取得済み
        NOT_SUPPORTED,  // この車両では非対応
        NO_PERMISSION,  // 権限なし
        WRITE_ONLY,     // 書き込み専用
        ERROR           // 取得・監視に失敗
    }

    // Continuous-value state / 連続値の状態
    private Boolean moving;
    private boolean overSpeed;
    private float lastSpeedMs = Float.NaN;
    private long lastSpeedTimestampNs;
    private long lastSuddenEventMs;
    private VehicleFlag steerZone;
    private Boolean lowRange;
    private Boolean nearFreezing;
    private Boolean charging;

    public VehicleFlagManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // =====================================================================
    // Start / stop / 開始・停止
    // =====================================================================

    /** Connects to the car service, reads fixed values and subscribes to the rest. / 接続して取得を開始する。 */
    public boolean start() {
        if (car != null) return true;
        car = Car.createCar(context);
        if (car == null) {
            Log.e(TAG, "Car service is not available / 車両サービスに接続できません");
            return false;
        }
        propertyManager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
        if (propertyManager == null) {
            Log.e(TAG, "CarPropertyManager is not available");
            return false;
        }
        for (int propertyId : ALL_PROPERTIES) {
            readOrSubscribe(propertyId);
        }
        // If a light switch works, its state property only supplies values, not flags.
        for (int[] pair : LIGHT_PAIRS) {
            if (supportedProperties.contains(pair[0])) flagMutedProperties.add(pair[1]);
        }
        Log.i(TAG, "Reading " + supportedProperties.size() + "/" + ALL_PROPERTIES.length + " properties");
        return true;
    }

    /** Stops listening and disconnects. Call in onDestroy(). / 停止して切断する。onDestroy() で呼ぶ。 */
    public void stop() {
        if (propertyManager != null) {
            propertyManager.unregisterCallback(callback);
            propertyManager = null;
        }
        if (car != null) {
            car.disconnect();
            car = null;
        }
        supportedProperties.clear();
        flagMutedProperties.clear();
        statusByProperty.clear();
    }

    // =====================================================================
    // Flags / フラグ
    // =====================================================================

    public void setFlagListener(FlagListener listener) { this.listener = listener; }

    /** True if the flag is raised (does not clear it). / フラグが立っているか（クリアしない）。 */
    public boolean isFlagSet(VehicleFlag flag) { return flags.contains(flag); }

    /** Returns true and clears the flag if it was raised. / 立っていれば true を返してクリアする。 */
    public boolean consumeFlag(VehicleFlag flag) { return flags.remove(flag); }

    public Set<VehicleFlag> getFlags() {
        return flags.isEmpty() ? EnumSet.noneOf(VehicleFlag.class) : EnumSet.copyOf(flags);
    }

    public void clearAllFlags() { flags.clear(); }

    // =====================================================================
    // Latest values / 最新値
    // =====================================================================

    /** Latest value for a global property (or its first area). null if not received. / 最新値（未取得なら null）。 */
    public Object getValue(int propertyId) {
        Object v = values.get(propertyId + ":0");
        if (v != null) return v;
        String prefix = propertyId + ":";
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) return e.getValue();
        }
        return null;
    }

    /** Latest value for one area (seat, window…). / エリア（座席・窓など）ごとの最新値。 */
    public Object getValue(int propertyId, int areaId) { return values.get(propertyId + ":" + areaId); }

    public float getFloat(int propertyId, float defaultValue) {
        Object v = getValue(propertyId);
        return v instanceof Float ? (Float) v : defaultValue;
    }

    public int getInt(int propertyId, int defaultValue) {
        Object v = getValue(propertyId);
        return v instanceof Integer ? (Integer) v : defaultValue;
    }

    public boolean getBoolean(int propertyId, boolean defaultValue) {
        Object v = getValue(propertyId);
        return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    // ---- Convenience getters / よく使う値 ----
    public float getSpeedKmh() { return Math.abs(getFloat(SPEED_PROPERTY, 0f)) * 3.6f; }
    public float getSteeringAngleDeg() { return getFloat(VehiclePropertyIds.PERF_STEERING_ANGLE, 0f); }
    public int getGear() { return getInt(VehiclePropertyIds.GEAR_SELECTION, 0); }
    public boolean isParked() { return getGear() == GEAR_PARK; }
    public float getOdometerKm() { return getFloat(VehiclePropertyIds.PERF_ODOMETER, Float.NaN); }
    public float getFuelLevelLiters() { return getFloat(VehiclePropertyIds.FUEL_LEVEL, Float.NaN) / 1000f; }
    public float getEvBatteryLevel() { return getFloat(VehiclePropertyIds.EV_BATTERY_LEVEL, Float.NaN); }
    public float getRangeRemainingKm() { return getFloat(VehiclePropertyIds.RANGE_REMAINING, Float.NaN) / 1000f; }
    public float getOutsideTemperatureC() { return getFloat(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, Float.NaN); }
    public int getIgnitionState() { return getInt(VehiclePropertyIds.IGNITION_STATE, 0); }

    /**
     * True when every occupied seat has its belt buckled (seats with unknown data are ignored).
     * 着座しているすべての席でシートベルトを着用していれば true。
     */
    public boolean areAllOccupiedSeatsBuckled() {
        for (Map.Entry<Integer, Boolean> e : occupiedBySeat.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && Boolean.FALSE.equals(beltBySeat.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Number of occupied seats, or -1 if seat occupancy is not available.
     * 着座している席の数（取得できない場合は -1）。
     */
    public int getOccupiedSeatCount() {
        if (occupiedBySeat.isEmpty()) return -1;
        int count = 0;
        for (Boolean occupied : occupiedBySeat.values()) {
            if (Boolean.TRUE.equals(occupied)) count++;
        }
        return count;
    }

    /** All latest values as "NAME [area 0x..]" → text, sorted. / 全最新値（名前順）。 */
    public Map<String, String> getAllValues() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String[] key = e.getKey().split(":");
            int propertyId = Integer.parseInt(key[0]);
            int areaId = Integer.parseInt(key[1]);
            String name = VehiclePropertyIds.toString(propertyId) + (areaId != 0 ? " [area 0x" + Integer.toHexString(areaId) + "]" : "");
            out.put(name, describe(e.getValue()));
        }
        return out;
    }

    /** Prints every latest value to Logcat. / 全最新値を Logcat に出力する。 */
    public void logAllValues() {
        for (Map.Entry<String, String> e : getAllValues().entrySet()) {
            Log.i(TAG, "Value / 値: " + e.getKey() + " = " + e.getValue());
        }
    }

    /** Property IDs that are supported (read or subscribed). / 取得できているプロパティ ID。 */
    public List<Integer> getSupportedProperties() {
        return Collections.unmodifiableList(new ArrayList<>(supportedProperties));
    }

    /** True while connected to the car service. / 車両サービスに接続中か。 */
    public boolean isConnected() { return propertyManager != null; }

    /** Every property this class tries to read, in support-list order. / 取得対象の全プロパティ ID。 */
    public static int[] getAllPropertyIds() { return ALL_PROPERTIES.clone(); }

    /** Read result of a property, or null before start(). / 取得結果（start() 前は null）。 */
    public PropertyStatus getPropertyStatus(int propertyId) { return statusByProperty.get(propertyId); }

    /** Last time a value arrived, or 0 if never. / 最後に値を受信した時刻（未受信なら 0）。 */
    public long getLastUpdateMs(int propertyId) {
        Long t = lastUpdateMs.get(propertyId);
        return t != null ? t : 0L;
    }

    /**
     * Latest values of one property as text, one line per area ("area 0x..: value").
     * Null if no value has been received yet. / 1プロパティの最新値（エリアごと）。未受信なら null。
     */
    public String describeValues(int propertyId) {
        String prefix = propertyId + ":";
        Map<Integer, String> byArea = new TreeMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                byArea.put(Integer.parseInt(e.getKey().substring(prefix.length())), describe(e.getValue()));
            }
        }
        if (byArea.isEmpty()) return null;
        if (byArea.size() == 1 && byArea.containsKey(0)) return byArea.get(0);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : byArea.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(getAreaLabel(propertyId, e.getKey())).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Readable area name, e.g. DOOR_POS area 0x4 → "前列・右 (0x4)". / エリア名（例：前列・右）。
     * Left / right are from the car's point of view (right-hand drive: 前列・右 = driver side).
     */
    public String getAreaLabel(int propertyId, int areaId) {
        Integer areaType = areaTypeByProperty.get(propertyId);
        return VehicleAreaNames.label(areaType != null ? areaType : -1, areaId);
    }

    // ---- Threshold setters / しきい値の設定 ----
    public void setOverSpeedKmh(float kmh) { overSpeedKmh = kmh; }
    public void setSuddenAccelMs2(float ms2) { suddenAccelMs2 = ms2; }
    public void setSteerHardDeg(float deg) { steerHardDeg = deg; }
    public void setLowRangeKm(float km) { lowRangeKm = km; }
    public void setFreezingC(float c) { freezingC = c; }

    // =====================================================================
    // Reading / 取得
    // =====================================================================

    /**
     * System-level (signature|privileged) permission needed to read a property, or null if not listed here.
     * Only properties whose permission is certain are listed. Normal apps cannot get these permissions.
     * 読取にシステム権限が必要なプロパティ（通常アプリには付与されない）。
     */
    private static String requiredSystemPermission(int propertyId) {
        switch (propertyId) {
            case VehiclePropertyIds.DOOR_POS:
            case VehiclePropertyIds.DOOR_LOCK:
            case VehiclePropertyIds.DOOR_CHILD_LOCK_ENABLED:
                return "android.car.permission.CONTROL_CAR_DOORS";
            case VehiclePropertyIds.WINDOW_POS:
            case VehiclePropertyIds.WINDOW_LOCK:
                return "android.car.permission.CONTROL_CAR_WINDOWS";
            case VehiclePropertyIds.PERF_ODOMETER:
                return "android.car.permission.CAR_MILEAGE";
            default:
                return null;
        }
    }

    private void readOrSubscribe(int propertyId) {
        String name = VehiclePropertyIds.toString(propertyId);
        try {
            CarPropertyConfig<?> config = propertyManager.getCarPropertyConfig(propertyId);
            if (config == null) {
                // The car service returns null both when the car lacks the property and when the app lacks
                // the permission. Tell them apart for properties whose permission is known.
                // 「非対応」と「権限なし」でどちらも null になるため、必要権限が分かるものは区別する。
                String permission = requiredSystemPermission(propertyId);
                if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    statusByProperty.put(propertyId, PropertyStatus.NO_PERMISSION);
                    Log.i(TAG, "No permission / 権限なし: " + name + " (" + permission + ")");
                } else {
                    statusByProperty.put(propertyId, PropertyStatus.NOT_SUPPORTED);
                    Log.i(TAG, "Not supported / 非対応: " + name);
                }
                return;
            }
            areaTypeByProperty.put(propertyId, config.getAreaType());
            if (config.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE) {
                statusByProperty.put(propertyId, PropertyStatus.WRITE_ONLY);
                Log.i(TAG, "Write-only, skipped / 書き込み専用: " + name);
                return;
            }
            if (config.getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC) {
                // Fixed value: read once per area. / 固定値：エリアごとに1回読む。
                int[] areaIds = config.getAreaIds();
                if (areaIds == null || areaIds.length == 0) areaIds = new int[]{0};
                for (int areaId : areaIds) {
                    CarPropertyValue<?> v = propertyManager.getProperty(propertyId, areaId);
                    if (v != null && v.getStatus() == CarPropertyValue.STATUS_AVAILABLE) {
                        values.put(propertyId + ":" + areaId, v.getValue());
                    }
                }
                supportedProperties.add(propertyId);
                statusByProperty.put(propertyId, PropertyStatus.READ_ONCE);
                lastUpdateMs.put(propertyId, System.currentTimeMillis());
                Log.i(TAG, "Read once / 固定値を取得: " + name);
                return;
            }
            float rate = config.getChangeMode() == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS
                    ? CarPropertyManager.SENSOR_RATE_NORMAL
                    : CarPropertyManager.SENSOR_RATE_ONCHANGE;
            if (propertyManager.registerCallback(callback, propertyId, rate)) {
                supportedProperties.add(propertyId);
                statusByProperty.put(propertyId, PropertyStatus.SUBSCRIBED);
                Log.i(TAG, "Subscribed / 監視開始: " + name + " (0x" + Integer.toHexString(propertyId) + ")");
            } else {
                statusByProperty.put(propertyId, PropertyStatus.ERROR);
                Log.w(TAG, "Subscribe failed / 監視できません: " + name);
            }
        } catch (SecurityException e) {
            statusByProperty.put(propertyId, PropertyStatus.NO_PERMISSION);
            Log.w(TAG, "No permission / 権限なし: " + name, e);
        } catch (RuntimeException e) {
            statusByProperty.put(propertyId, PropertyStatus.ERROR);
            Log.w(TAG, "Cannot read / 取得できません: " + name, e);
        }
    }

    private final CarPropertyManager.CarPropertyEventCallback callback =
            new CarPropertyManager.CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    if (value == null || value.getStatus() != CarPropertyValue.STATUS_AVAILABLE) return;
                    try {
                        handle(value.getPropertyId(), value.getAreaId(), value.getValue(), value.getTimestamp());
                    } catch (ClassCastException e) {
                        Log.w(TAG, "Unexpected value type: " + VehiclePropertyIds.toString(value.getPropertyId()), e);
                    }
                }

                @Override
                public void onErrorEvent(int propertyId, int areaId) {
                    Log.w(TAG, "Error event: " + VehiclePropertyIds.toString(propertyId) + " area=" + areaId);
                }
            };

    // =====================================================================
    // Flag logic / フラグ判定
    // =====================================================================

    private void handle(int propertyId, int areaId, Object value, long timestampNs) {
        Object previous = values.put(propertyId + ":" + areaId, value);
        lastUpdateMs.put(propertyId, System.currentTimeMillis());

        // Continuous values: judged on every sample. / 連続値は毎回判定。
        if (propertyId == SPEED_PROPERTY) {
            handleSpeed((Float) value, timestampNs);
            return;
        }
        if (propertyId == VehiclePropertyIds.PERF_STEERING_ANGLE) {
            handleSteering((Float) value);
            return;
        }
        if (propertyId == VehiclePropertyIds.RANGE_REMAINING) {
            boolean low = (Float) value / 1000f <= lowRangeKm;
            if (lowRange != null && low && !lowRange) raise(VehicleFlag.LOW_RANGE, areaId, value);
            lowRange = low;
            return;
        }
        if (propertyId == VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE) {
            float c = (Float) value;
            if (nearFreezing == null) {
                nearFreezing = c <= freezingC;
            } else if (!nearFreezing && c <= freezingC) {
                nearFreezing = true;
                raise(VehicleFlag.NEAR_FREEZING, areaId, value);
            } else if (nearFreezing && c > freezingC + 1f) {
                nearFreezing = false;
            }
            return;
        }
        if (propertyId == VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE) {
            boolean nowCharging = (Float) value > 0f;
            if (charging != null && charging != nowCharging) {
                raise(nowCharging ? VehicleFlag.CHARGING_STARTED : VehicleFlag.CHARGING_STOPPED, areaId, value);
            }
            charging = nowCharging;
            return;
        }

        // Seat maps are updated even for the first value. / 座席の状態は初回でも記録。
        if (propertyId == VehiclePropertyIds.SEAT_BELT_BUCKLED) {
            beltBySeat.put(areaId, (Boolean) value);
        } else if (propertyId == VehiclePropertyIds.SEAT_OCCUPANCY) {
            occupiedBySeat.put(areaId, (Integer) value == SEAT_OCCUPIED);
        }

        // Everything else raises a flag only when the value changes. / その他は変化した時だけ。
        if (previous == null || previous.equals(value)) return;
        if (flagMutedProperties.contains(propertyId)) return;

        switch (propertyId) {
            // ---- Lights / ライト ----
            case VehiclePropertyIds.HEADLIGHTS_SWITCH:
            case VehiclePropertyIds.HEADLIGHTS_STATE:
                light(value, areaId, VehicleFlag.LOW_BEAM_ON, VehicleFlag.LOW_BEAM_OFF, VehicleFlag.LOW_BEAM_AUTO);
                break;
            case VehiclePropertyIds.HIGH_BEAM_LIGHTS_SWITCH:
            case VehiclePropertyIds.HIGH_BEAM_LIGHTS_STATE:
                light(value, areaId, VehicleFlag.HIGH_BEAM_ON, VehicleFlag.HIGH_BEAM_OFF, VehicleFlag.HIGH_BEAM_AUTO);
                break;
            case VehiclePropertyIds.FOG_LIGHTS_SWITCH:
            case VehiclePropertyIds.FOG_LIGHTS_STATE:
                light(value, areaId, VehicleFlag.FOG_ON, VehicleFlag.FOG_OFF, null);
                break;
            case VehiclePropertyIds.HAZARD_LIGHTS_SWITCH:
            case VehiclePropertyIds.HAZARD_LIGHTS_STATE:
                light(value, areaId, VehicleFlag.HAZARD_ON, VehicleFlag.HAZARD_OFF, null);
                break;
            case VehiclePropertyIds.CABIN_LIGHTS_SWITCH:
                light(value, areaId, VehicleFlag.CABIN_LIGHT_ON, VehicleFlag.CABIN_LIGHT_OFF, null);
                break;
            case VehiclePropertyIds.READING_LIGHTS_SWITCH:
                light(value, areaId, VehicleFlag.READING_LIGHT_ON, VehicleFlag.READING_LIGHT_OFF, null);
                break;
            case VehiclePropertyIds.TURN_SIGNAL_STATE: {
                int signal = (Integer) value;
                if (signal == TURN_LEFT) raise(VehicleFlag.TURN_LEFT_ON, areaId, value);
                else if (signal == TURN_RIGHT) raise(VehicleFlag.TURN_RIGHT_ON, areaId, value);
                else if (signal == TURN_NONE) raise(VehicleFlag.TURN_SIGNAL_OFF, areaId, value);
                break;
            }

            // ---- Driving / 走行 ----
            case VehiclePropertyIds.GEAR_SELECTION: {
                int gear = (Integer) value;
                if (gear == GEAR_PARK) raise(VehicleFlag.GEAR_PARK, areaId, value);
                else if (gear == GEAR_REVERSE) raise(VehicleFlag.GEAR_REVERSE, areaId, value);
                else if (gear == GEAR_NEUTRAL) raise(VehicleFlag.GEAR_NEUTRAL, areaId, value);
                else if (gear == GEAR_DRIVE) raise(VehicleFlag.GEAR_DRIVE, areaId, value);
                break;
            }
            case VehiclePropertyIds.PARKING_BRAKE_ON:
                onOff(value, areaId, VehicleFlag.PARKING_BRAKE_ON, VehicleFlag.PARKING_BRAKE_OFF);
                break;
            case VehiclePropertyIds.IGNITION_STATE: {
                int state = (Integer) value;
                if (state == IGNITION_LOCK || state == IGNITION_OFF) raise(VehicleFlag.IGNITION_OFF, areaId, value);
                else if (state == IGNITION_ACC) raise(VehicleFlag.IGNITION_ACC, areaId, value);
                else if (state == IGNITION_ON) raise(VehicleFlag.IGNITION_ON, areaId, value);
                else if (state == IGNITION_START) raise(VehicleFlag.ENGINE_START, areaId, value);
                break;
            }

            // ---- Energy / エネルギー ----
            case VehiclePropertyIds.FUEL_LEVEL_LOW:
                onOff(value, areaId, VehicleFlag.FUEL_LOW_ON, VehicleFlag.FUEL_LOW_OFF);
                break;

            // ---- Air conditioner / エアコン ----
            case VehiclePropertyIds.HVAC_POWER_ON:
                onOff(value, areaId, VehicleFlag.HVAC_POWER_ON, VehicleFlag.HVAC_POWER_OFF);
                break;
            case VehiclePropertyIds.HVAC_FAN_SPEED:
                upDown(value, previous, areaId, VehicleFlag.FAN_SPEED_UP, VehicleFlag.FAN_SPEED_DOWN);
                break;
            case VehiclePropertyIds.HVAC_TEMPERATURE_SET:
                upDown(value, previous, areaId, VehicleFlag.TEMP_UP, VehicleFlag.TEMP_DOWN);
                break;
            case VehiclePropertyIds.HVAC_FAN_DIRECTION: {
                boolean defNow = ((Integer) value & FAN_DIRECTION_DEFROST) != 0;
                boolean defBefore = ((Integer) previous & FAN_DIRECTION_DEFROST) != 0;
                if (defNow != defBefore) {
                    raise(defNow ? VehicleFlag.DEFROST_MODE_ON : VehicleFlag.DEFROST_MODE_OFF, areaId, value);
                }
                break;
            }
            case VehiclePropertyIds.HVAC_DEFROSTER: {
                boolean on = (Boolean) value;
                if ((areaId & WINDOW_FRONT_WINDSHIELD) != 0) {
                    raise(on ? VehicleFlag.FRONT_DEFROSTER_ON : VehicleFlag.FRONT_DEFROSTER_OFF, areaId, value);
                }
                if ((areaId & WINDOW_REAR_WINDSHIELD) != 0) {
                    raise(on ? VehicleFlag.REAR_DEFROSTER_ON : VehicleFlag.REAR_DEFROSTER_OFF, areaId, value);
                }
                break;
            }
            case VehiclePropertyIds.HVAC_ELECTRIC_DEFROSTER_ON:
                onOff(value, areaId, VehicleFlag.ELECTRIC_DEFROSTER_ON, VehicleFlag.ELECTRIC_DEFROSTER_OFF);
                break;
            case VehiclePropertyIds.HVAC_AC_ON:
                onOff(value, areaId, VehicleFlag.AC_ON, VehicleFlag.AC_OFF);
                break;
            case VehiclePropertyIds.HVAC_MAX_AC_ON:
                onOff(value, areaId, VehicleFlag.MAX_AC_ON, VehicleFlag.MAX_AC_OFF);
                break;
            case VehiclePropertyIds.HVAC_RECIRC_ON:
                onOff(value, areaId, VehicleFlag.RECIRC_ON, VehicleFlag.RECIRC_OFF);
                break;
            case VehiclePropertyIds.HVAC_DUAL_ON:
                onOff(value, areaId, VehicleFlag.DUAL_ON, VehicleFlag.DUAL_OFF);
                break;
            case VehiclePropertyIds.HVAC_AUTO_ON:
                onOff(value, areaId, VehicleFlag.HVAC_AUTO_ON, VehicleFlag.HVAC_AUTO_OFF);
                break;
            case VehiclePropertyIds.HVAC_SEAT_TEMPERATURE:
                upDown(value, previous, areaId, VehicleFlag.SEAT_HEAT_UP, VehicleFlag.SEAT_HEAT_DOWN);
                break;
            case VehiclePropertyIds.HVAC_SEAT_VENTILATION:
                upDown(value, previous, areaId, VehicleFlag.SEAT_VENT_UP, VehicleFlag.SEAT_VENT_DOWN);
                break;
            case VehiclePropertyIds.HVAC_STEERING_WHEEL_HEAT:
                raise((Integer) value != 0 ? VehicleFlag.STEERING_HEAT_ON : VehicleFlag.STEERING_HEAT_OFF, areaId, value);
                break;

            // ---- Seats / シート ----
            case VehiclePropertyIds.SEAT_BELT_BUCKLED:
                onOff(value, areaId, VehicleFlag.SEATBELT_BUCKLED, VehicleFlag.SEATBELT_UNBUCKLED);
                break;
            case VehiclePropertyIds.SEAT_OCCUPANCY: {
                int state = (Integer) value;
                if (state == SEAT_OCCUPIED) raise(VehicleFlag.SEAT_OCCUPIED, areaId, value);
                else if (state == SEAT_VACANT) raise(VehicleFlag.SEAT_VACATED, areaId, value);
                break;
            }

            // ---- Windows / 窓 ----
            case VehiclePropertyIds.WINDOW_POS:
                upDown(value, previous, areaId, VehicleFlag.WINDOW_OPENED, VehicleFlag.WINDOW_CLOSED);
                break;
            case VehiclePropertyIds.WINDOW_LOCK:
                onOff(value, areaId, VehicleFlag.WINDOW_LOCK_ON, VehicleFlag.WINDOW_LOCK_OFF);
                break;

            // ---- ETC ----
            case VehiclePropertyIds.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS: {
                int status = (Integer) value;
                if (status == ETC_VALID) raise(VehicleFlag.ETC_CARD_VALID, areaId, value);
                else if (status == ETC_INVALID) raise(VehicleFlag.ETC_CARD_INVALID, areaId, value);
                else if (status == ETC_NOT_INSERTED) raise(VehicleFlag.ETC_CARD_REMOVED, areaId, value);
                break;
            }

            // Values only (no flag): odometer, speed display, wheel tick, fuel / battery level,
            // current cabin temperature, ETC card type. / 値のみ保持（フラグなし）。
            default:
                break;
        }
    }

    /** Speed in m/s (negative when reversing). / 車速 [m/s]（後退時は負）。 */
    private void handleSpeed(float speedMs, long timestampNs) {
        float absMs = Math.abs(speedMs);
        float kmh = absMs * 3.6f;

        boolean nowMoving = kmh > movingKmh;
        if (moving != null && moving != nowMoving) {
            raise(nowMoving ? VehicleFlag.VEHICLE_STARTED_MOVING : VehicleFlag.VEHICLE_STOPPED, 0, speedMs);
        }
        moving = nowMoving;

        if (!overSpeed && kmh >= overSpeedKmh) {
            overSpeed = true;
            raise(VehicleFlag.OVER_SPEED, 0, speedMs);
        } else if (overSpeed && kmh < overSpeedKmh - overSpeedHysteresisKmh) {
            overSpeed = false;
        }

        if (!Float.isNaN(lastSpeedMs) && lastSpeedTimestampNs > 0) {
            double dt = (timestampNs - lastSpeedTimestampNs) / 1e9;
            long nowMs = System.currentTimeMillis();
            if (dt > 0.05 && nowMs - lastSuddenEventMs > suddenCooldownMs) {
                double accel = (absMs - lastSpeedMs) / dt;
                if (accel >= suddenAccelMs2) {
                    lastSuddenEventMs = nowMs;
                    raise(VehicleFlag.SUDDEN_ACCELERATION, 0, (float) accel);
                } else if (accel <= -suddenAccelMs2) {
                    lastSuddenEventMs = nowMs;
                    raise(VehicleFlag.SUDDEN_BRAKING, 0, (float) accel);
                }
            }
        }
        lastSpeedMs = absMs;
        lastSpeedTimestampNs = timestampNs;
    }

    /**
     * Steering angle in degrees (support list range: -756.0 to 756.0). AOSP defines left as negative;
     * the support list does not say, so confirm the sign on the emulator.
     * ステアリング角 [deg]（範囲 -756.0〜756.0）。AOSP では左が負。符号はエミュレーターで確認すること。
     */
    private void handleSteering(float degrees) {
        VehicleFlag zone;
        if (degrees <= -steerHardDeg) zone = VehicleFlag.STEER_LEFT_HARD;
        else if (degrees >= steerHardDeg) zone = VehicleFlag.STEER_RIGHT_HARD;
        else if (Math.abs(degrees) <= steerCenterDeg) zone = VehicleFlag.STEER_CENTER;
        else return;

        if (steerZone != null && steerZone != zone) raise(zone, 0, degrees);
        steerZone = zone;
    }

    // ---- Helpers / 補助 ----

    /** Light switch/state: 0=OFF, 1=ON, 2=DAYTIME_RUNNING (no flag), 256=AUTOMATIC. */
    private void light(Object value, int areaId, VehicleFlag on, VehicleFlag off, VehicleFlag auto) {
        int v = (Integer) value;
        if (v == LIGHT_ON) raise(on, areaId, value);
        else if (v == LIGHT_OFF) raise(off, areaId, value);
        else if (v == LIGHT_AUTOMATIC && auto != null) raise(auto, areaId, value);
    }

    private void onOff(Object value, int areaId, VehicleFlag on, VehicleFlag off) {
        raise((Boolean) value ? on : off, areaId, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void upDown(Object value, Object previous, int areaId, VehicleFlag up, VehicleFlag down) {
        int cmp = ((Comparable) value).compareTo(previous);
        if (cmp > 0) raise(up, areaId, value);
        else if (cmp < 0) raise(down, areaId, value);
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        if (v instanceof Object[]) return Arrays.toString((Object[]) v);
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        return String.valueOf(v);
    }

    private void raise(VehicleFlag flag, int areaId, Object value) {
        flags.add(flag);
        Log.d(TAG, "Flag / フラグ: " + flag + " area=0x" + Integer.toHexString(areaId) + " value=" + value);
        FlagListener l = listener;
        if (l != null) l.onFlagRaised(flag, areaId, value);
    }
}
