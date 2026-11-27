package com.chenan.flymebobo;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;

/**
 * flymebobo 2.4.6 (v546) — 一条通知自带的「可操作动作」快照，
 * 供长按胶囊菜单（快捷回复 / 标为已读）使用。
 *
 * 数据来源：NotifCollectionHook 在 SystemUI 进程里逐条读到
 * 真实的 Notification 对象时抽取，按通知 key 暂存到
 * BubbleWindow 的静态 Map 里。这些对象（PendingIntent /
 * RemoteInput）只在 SystemUI 进程内使用，绝不塞进广播
 * extras 跨进程传递（广播路径弹出的胶囊也能命中这份暂存，
 * 因为 Hook 在 SystemUI 里见过每一条通知）。
 *
 * 菜单项严格按能力出现：没有 RemoteInput 回复动作就没有
 * 「回复」，没有已读类动作就没有「标为已读」——不放死按钮。
 */
public final class NotifActions {

    /** 带自由文本 RemoteInput 的回复动作的发送意图。 */
    public final PendingIntent replyIntent;
    /** 回复动作用的 RemoteInput（取结果键、拼回执）。 */
    public final RemoteInput remoteInput;
    /** 标题命中已读类的动作的发送意图（可为 null）。 */
    public final PendingIntent markReadIntent;

    private NotifActions(PendingIntent replyIntent,
            RemoteInput remoteInput, PendingIntent markReadIntent) {
        this.replyIntent = replyIntent;
        this.remoteInput = remoteInput;
        this.markReadIntent = markReadIntent;
    }

    public boolean hasReply() {
        return replyIntent != null && remoteInput != null;
    }

    public boolean hasMarkRead() {
        return markReadIntent != null;
    }

    public boolean isEmpty() {
        return !hasReply() && !hasMarkRead();
    }

    /** 已读类动作的标题口径：中英都认（包含匹配）。 */
    private static boolean isMarkReadTitle(String t) {
        if (t == null) {
            return false;
        }
        String s = t.trim();
        if (s.contains("标为已读") || s.contains("标记已读")
                || s.contains("標為已讀") || s.contains("標記已讀")
                || s.equals("已读") || s.equals("已讀")) {
            return true;
        }
        String low = s.toLowerCase(java.util.Locale.US);
        return low.contains("mark as read") || low.contains("mark read");
    }

    /** extras 里 WearableExtender 的键（公开结构常量）。 */
    private static final String KEY_WEARABLE_EXTENSIONS =
            "android.wearable.EXTENSIONS";

    /** 动作里是否有自由文本 RemoteInput（能不能直接回复）。 */
    private static RemoteInput freeFormInput(Notification.Action a) {
        try {
            RemoteInput[] ris = a.getRemoteInputs();
            if (ris != null) {
                for (RemoteInput ri : ris) {
                    if (ri != null && ri.getAllowFreeFormInput()) {
                        return ri;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Telegram 等应用可能把回复动作藏在 WearableExtender 里
     * 而不是 notification.actions：extras 的
     * android.wearable.EXTENSIONS Bundle 里 "actions" 键存的
     * 就是 Parcelable 的 Notification.Action 数组，同进程内
     * 直接取回。取不到（结构不同/类加载失败）回 null，不硬凑。
     */
    private static Notification.Action[] wearableActions(
            Notification n, boolean[] hasWearableOut) {
        try {
            android.os.Bundle extras = n.extras;
            if (extras == null) {
                return null;
            }
            android.os.Bundle w = null;
            try {
                w = extras.getBundle(KEY_WEARABLE_EXTENSIONS);
            } catch (Throwable ignored) {
            }
            if (w == null) {
                hasWearableOut[0] = extras.containsKey(
                        KEY_WEARABLE_EXTENSIONS);
                return null;
            }
            hasWearableOut[0] = true;
            try {
                w.setClassLoader(Notification.class.getClassLoader());
            } catch (Throwable ignored) {
            }
            android.os.Parcelable[] arr = null;
            try {
                arr = w.getParcelableArray("actions");
            } catch (Throwable ignored) {
            }
            if (arr == null || arr.length == 0) {
                return null;
            }
            java.util.ArrayList<Notification.Action> out =
                    new java.util.ArrayList<>();
            for (android.os.Parcelable p : arr) {
                if (p instanceof Notification.Action) {
                    out.add((Notification.Action) p);
                }
            }
            return out.isEmpty() ? null
                    : out.toArray(new Notification.Action[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从真实 Notification 里抽取动作；什么都没有回 null。
     * v548：notification.actions 找不到回复时，再翻
     * WearableExtender 的动作列表。两处都没有回复动作时打
     * 一条诊断日志（QuickReply diag），把这条通知实际带的
     * 动作摘要打出来，方便定性——只在没找到时打，防刷屏。
     */
    public static NotifActions extract(Notification n, String pkg) {
        if (n == null) {
            return null;
        }
        boolean[] hasWearable = new boolean[]{false};
        Notification.Action[] wActions = wearableActions(n, hasWearable);
        PendingIntent replyIntent = null;
        RemoteInput remoteInput = null;
        PendingIntent markReadIntent = null;
        StringBuilder diag = new StringBuilder();
        // 先扫 notification.actions，再扫 wearable 的（摘要里
        // wearable 的标题带 w: 前缀区分来源）。
        Notification.Action[][] groups = new Notification.Action[][]{
                n.actions, wActions};
        for (int gi = 0; gi < groups.length; gi++) {
            Notification.Action[] arr = groups[gi];
            if (arr == null) {
                continue;
            }
            for (Notification.Action a : arr) {
                if (a == null) {
                    continue;
                }
                RemoteInput fri = freeFormInput(a);
                if (diag.length() > 0) {
                    diag.append(", ");
                }
                if (gi == 1) {
                    diag.append("w:");
                }
                diag.append(a.title == null ? "null"
                        : a.title.toString());
                diag.append("|ri=").append(fri != null);
                if (a.actionIntent == null) {
                    continue;
                }
                if (replyIntent == null && fri != null) {
                    replyIntent = a.actionIntent;
                    remoteInput = fri;
                }
                if (markReadIntent == null && a.title != null
                        && isMarkReadTitle(a.title.toString())) {
                    markReadIntent = a.actionIntent;
                }
            }
        }
        if (replyIntent == null) {
            FileLog.i("QuickReply diag pkg=" + pkg
                    + " actions=[" + diag + "] wearable="
                    + hasWearable[0]);
        }
        NotifActions out = new NotifActions(
                replyIntent, remoteInput, markReadIntent);
        return out.isEmpty() ? null : out;
    }
}
