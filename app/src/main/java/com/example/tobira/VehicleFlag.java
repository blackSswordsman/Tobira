package com.example.tobira;

/**
 * Flags raised when the driver or a passenger performs an operation in the car.
 * 車内で操作が行われたときに立つフラグの一覧。
 *
 * A flag is raised only when a value CHANGES (edge-triggered).
 * The first value read at start-up is treated as the initial state, not as an operation.
 * フラグは値が「変化したとき」だけ立つ。起動直後の最初の値は初期状態として扱い、フラグは立てない。
 */
public enum VehicleFlag {

    // ---- Lights / ライト ----
    LOW_BEAM_ON,          // ロービーム点灯
    LOW_BEAM_OFF,         // ロービーム消灯
    LOW_BEAM_AUTO,        // ライトスイッチ AUTO
    HIGH_BEAM_ON,         // ハイビーム点灯
    HIGH_BEAM_OFF,        // ハイビーム消灯
    HIGH_BEAM_AUTO,       // ハイビームスイッチ AUTO
    FOG_ON,               // フォグ点灯
    FOG_OFF,              // フォグ消灯
    TURN_LEFT_ON,         // 左ウィンカー
    TURN_RIGHT_ON,        // 右ウィンカー
    TURN_SIGNAL_OFF,      // ウィンカー消灯
    HAZARD_ON,            // ハザード点灯
    HAZARD_OFF,           // ハザード消灯
    CABIN_LIGHT_ON,       // 室内灯点灯
    CABIN_LIGHT_OFF,      // 室内灯消灯
    READING_LIGHT_ON,     // 読書灯（マップランプ）点灯
    READING_LIGHT_OFF,    // 読書灯（マップランプ）消灯

    // ---- Speed / 車速 ----
    VEHICLE_STARTED_MOVING, // 発進
    VEHICLE_STOPPED,        // 停止
    OVER_SPEED,             // 設定速度を超えた
    SUDDEN_ACCELERATION,    // 急加速
    SUDDEN_BRAKING,         // 急ブレーキ

    // ---- Steering / ステアリング角 ----
    STEER_LEFT_HARD,      // 左に大きく切った
    STEER_RIGHT_HARD,     // 右に大きく切った
    STEER_CENTER,         // 中央に戻した

    // ---- Gear / ギヤ ----
    GEAR_PARK,            // P
    GEAR_REVERSE,         // R
    GEAR_NEUTRAL,         // N
    GEAR_DRIVE,           // D

    // ---- Parking brake / パーキングブレーキ ----
    PARKING_BRAKE_ON,
    PARKING_BRAKE_OFF,

    // ---- Air conditioner / エアコン ----
    FAN_SPEED_UP,         // 風量アップ
    FAN_SPEED_DOWN,       // 風量ダウン
    TEMP_UP,              // 温度アップ
    TEMP_DOWN,            // 温度ダウン
    FRONT_DEFROSTER_ON,   // フロントデフロスターON
    FRONT_DEFROSTER_OFF,
    REAR_DEFROSTER_ON,    // リアデフロスターON
    REAR_DEFROSTER_OFF,
    DEFROST_MODE_ON,      // 吹出口を DEF（デフロスター）に切り替えた
    DEFROST_MODE_OFF,     // DEF 以外に戻した
    ELECTRIC_DEFROSTER_ON,  // 電熱デフロスターON
    ELECTRIC_DEFROSTER_OFF,

    AC_ON,                // A/C ON
    AC_OFF,
    MAX_AC_ON,            // MAX AC ON
    MAX_AC_OFF,
    RECIRC_ON,            // 内気循環 ON
    RECIRC_OFF,
    DUAL_ON,              // 左右独立温度 ON
    DUAL_OFF,
    HVAC_AUTO_ON,         // エアコン AUTO ON
    HVAC_AUTO_OFF,
    HVAC_POWER_ON,        // エアコン電源 ON
    HVAC_POWER_OFF,
    SEAT_HEAT_UP,         // シートヒーター（温↑・冷↓）
    SEAT_HEAT_DOWN,
    SEAT_VENT_UP,         // シートベンチレーション
    SEAT_VENT_DOWN,
    STEERING_HEAT_ON,     // ハンドルヒーター
    STEERING_HEAT_OFF,

    // ---- Ignition / イグニッション ----
    IGNITION_OFF,         // LOCK / OFF
    IGNITION_ACC,         // ACC
    IGNITION_ON,          // ON
    ENGINE_START,         // START

    // ---- Energy / エネルギー ----
    FUEL_LOW_ON,          // 燃料残量警告 点灯
    FUEL_LOW_OFF,
    LOW_RANGE,            // 航続可能距離がしきい値以下になった
    CHARGING_STARTED,     // 充電開始（EV）
    CHARGING_STOPPED,     // 充電終了（EV）

    // ---- Environment / 環境 ----
    NEAR_FREEZING,        // 外気温がしきい値以下（凍結注意）

    // ---- Windows / 窓 ----
    WINDOW_OPENED,        // 窓を開けた
    WINDOW_CLOSED,        // 窓を閉めた
    WINDOW_LOCK_ON,       // チャイルドロック ON
    WINDOW_LOCK_OFF,

    // ---- ETC ----
    ETC_CARD_VALID,       // 有効な ETC カードが挿入された
    ETC_CARD_INVALID,     // 期限切れなど無効なカード
    ETC_CARD_REMOVED,     // カードが抜かれた

    // ---- Seat belt / シートベルト ----
    SEATBELT_BUCKLED,     // 着用
    SEATBELT_UNBUCKLED,   // 外した

    // ---- Seat occupancy / 着座検知 ----
    SEAT_OCCUPIED,        // 着座
    SEAT_VACATED          // 離席
}
