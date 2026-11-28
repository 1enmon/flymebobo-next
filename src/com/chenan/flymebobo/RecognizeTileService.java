package com.chenan.flymebobo;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * v647: 控制中心磁贴「识别屏幕」——按一下，
 * 广播给 SystemUI 识别当前屏上的票和码（扫光
 * 动画 + 悬浮结果卡）。磁贴本身只发广播，重活
 * 在 SystemUI 的 BubbleWindow 里。
 */
public class RecognizeTileService extends TileService {

    public static final String ACTION_TILE_RECOGNIZE =
            "com.chenan.flymebobo.TILE_RECOGNIZE";

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshState();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refreshState();
    }

    private void refreshState() {
        try {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            boolean on = getSharedPreferences(
                    "flymebobo_features", 0).getBoolean(
                            "manualRecognize", true);
            tile.setState(on
                    ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("识别屏幕");
            tile.setContentDescription("识别屏幕上的票和码");
            tile.updateTile();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            boolean on = getSharedPreferences(
                    "flymebobo_features", 0).getBoolean(
                            "manualRecognize", true);
            if (!on) {
                return;
            }
            // v648: 磁贴取字链先停用（v647 实测会
            // 引发 SystemUI 重启，定位中）——只给个
            // 提示，不惊动 SystemUI。
            android.widget.Toast.makeText(this,
                    "识别功能维护中，马上修",
                    android.widget.Toast.LENGTH_SHORT)
                    .show();
        } catch (Throwable ignored) {
        }
    }
}
