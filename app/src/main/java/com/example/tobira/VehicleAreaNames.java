package com.example.tobira;

import android.car.VehicleAreaSeat;
import android.car.VehicleAreaType;
import android.car.VehicleAreaWheel;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an area ID (which door / seat / window…) into a readable Japanese name.
 * エリア ID（どのドア・座席・窓か）を読みやすい名前に変換する。
 *
 * <p>Left / right are from the car's point of view. On a right-hand-drive car, 前列・右 is the driver side.
 * 左右は車両から見た向き。右ハンドル車では「前列・右」が運転席側。
 *
 * <p>Door / window / mirror values are the AOSP constants (VehicleAreaDoor etc. are hidden in the public SDK).
 * ドア・窓・ミラーの値は AOSP の定数（公開 SDK では非公開のため直接記載）。
 */
final class VehicleAreaNames {

    private VehicleAreaNames() {}

    // VehicleAreaDoor
    private static final int[] DOOR_BITS = {
            0x0001, 0x0004,           // ROW_1_LEFT, ROW_1_RIGHT
            0x0010, 0x0040,           // ROW_2_LEFT, ROW_2_RIGHT
            0x0100, 0x0400,           // ROW_3_LEFT, ROW_3_RIGHT
            0x10000000, 0x20000000,   // HOOD, REAR
    };
    private static final String[] DOOR_NAMES = {
            "前列・左", "前列・右", "後列・左", "後列・右", "3列目・左", "3列目・右", "ボンネット", "トランク",
    };

    private static final int[] SEAT_BITS = {
            VehicleAreaSeat.SEAT_ROW_1_LEFT, VehicleAreaSeat.SEAT_ROW_1_CENTER, VehicleAreaSeat.SEAT_ROW_1_RIGHT,
            VehicleAreaSeat.SEAT_ROW_2_LEFT, VehicleAreaSeat.SEAT_ROW_2_CENTER, VehicleAreaSeat.SEAT_ROW_2_RIGHT,
            VehicleAreaSeat.SEAT_ROW_3_LEFT, VehicleAreaSeat.SEAT_ROW_3_CENTER, VehicleAreaSeat.SEAT_ROW_3_RIGHT,
    };
    private static final String[] SEAT_NAMES = {
            "前列・左", "前列・中央", "前列・右",
            "後列・左", "後列・中央", "後列・右",
            "3列目・左", "3列目・中央", "3列目・右",
    };

    // VehicleAreaWindow
    private static final int[] WINDOW_BITS = {
            0x00001, 0x00002,         // FRONT_WINDSHIELD, REAR_WINDSHIELD
            0x00010, 0x00040,         // ROW_1_LEFT, ROW_1_RIGHT
            0x00100, 0x00400,         // ROW_2_LEFT, ROW_2_RIGHT
            0x01000, 0x04000,         // ROW_3_LEFT, ROW_3_RIGHT
            0x10000, 0x20000,         // ROOF_TOP_1, ROOF_TOP_2
    };
    private static final String[] WINDOW_NAMES = {
            "フロントガラス", "リアガラス",
            "前列・左", "前列・右", "後列・左", "後列・右", "3列目・左", "3列目・右",
            "ルーフ1", "ルーフ2",
    };

    private static final int[] WHEEL_BITS = {
            VehicleAreaWheel.WHEEL_LEFT_FRONT, VehicleAreaWheel.WHEEL_RIGHT_FRONT,
            VehicleAreaWheel.WHEEL_LEFT_REAR, VehicleAreaWheel.WHEEL_RIGHT_REAR,
    };
    private static final String[] WHEEL_NAMES = { "左前輪", "右前輪", "左後輪", "右後輪" };

    // VehicleAreaMirror
    private static final int[] MIRROR_BITS = { 0x1, 0x2, 0x4 }; // DRIVER_LEFT, DRIVER_RIGHT, DRIVER_CENTER
    private static final String[] MIRROR_NAMES = { "左ミラー", "右ミラー", "中央ミラー" };

    /**
     * e.g. (DOOR, 0x4) → "前列・右 (0x4)". Several areas combined → "前列・左+前列・右 (0x5)".
     * Unknown bits are kept as hex so nothing is hidden.
     */
    static String label(int areaType, int areaId) {
        String hex = "0x" + Integer.toHexString(areaId);
        int[] bits;
        String[] names;
        if (areaType == VehicleAreaType.VEHICLE_AREA_TYPE_DOOR) { bits = DOOR_BITS; names = DOOR_NAMES; }
        else if (areaType == VehicleAreaType.VEHICLE_AREA_TYPE_SEAT) { bits = SEAT_BITS; names = SEAT_NAMES; }
        else if (areaType == VehicleAreaType.VEHICLE_AREA_TYPE_WINDOW) { bits = WINDOW_BITS; names = WINDOW_NAMES; }
        else if (areaType == VehicleAreaType.VEHICLE_AREA_TYPE_WHEEL) { bits = WHEEL_BITS; names = WHEEL_NAMES; }
        else if (areaType == VehicleAreaType.VEHICLE_AREA_TYPE_MIRROR) { bits = MIRROR_BITS; names = MIRROR_NAMES; }
        else return "area " + hex;

        List<String> parts = new ArrayList<>();
        int unknown = areaId;
        for (int i = 0; i < bits.length; i++) {
            if ((areaId & bits[i]) != 0) {
                parts.add(names[i]);
                unknown &= ~bits[i];
            }
        }
        if (unknown != 0) parts.add("0x" + Integer.toHexString(unknown));
        if (parts.isEmpty()) return "area " + hex;
        return String.join("+", parts) + " (" + hex + ")";
    }
}
