package com.chenan.flymebobo;

import java.util.ArrayList;
import java.util.List;

/**
 * flymebobo 2.0 — 同应用通知组 (NotifGroup).
 *
 * Notifications from the same app automatically stack into one group.
 * The group shows a single capsule (the latest notification, with a
 * count badge when there is more than one). Tapping the capsule
 * expands the group DOWNWARD, revealing each notification as its own
 * capsule below the group capsule; tapping again collapses it.
 *
 * Fresh code for 2.0 (not copied from 1.x).
 */
public class NotifGroup {
    public final String pkg;
    /** Newest first (notification-time order, descending). */
    public final List<NotifData> items = new ArrayList<>();
    public boolean expanded;
    /** Pinned: the group stays out and shows the outside pin badge. */
    public boolean pinned;
    /** Pin time (ms) of each pinned notification in this group,
     * by key. Pin is per-NOTIFICATION (user 2026-11-19 "每条通知都按
     * 照单通知来看" / "钉住一个也应该能钉住其他的"): several
     * notifications of one app can be pinned, each independently.
     * group.pinned is true while this map is non-empty. Pinned
     * notifications sort by PIN time (earliest pinned first, v57),
     * not by notification time (user 2026-11-19 06:08 "同一应用不同
     * 通知钉住也要按正常钉住排序"). */
    public final java.util.Map<String, Long> pinnedAtByKey =
            new java.util.HashMap<>();
    /** When the group was pinned (ms); 0 = not pinned. Pinned groups
     * keep pin order (earliest pinned first), not notification time. */
    public long pinnedAt;

    public NotifGroup(String pkg) {
        this.pkg = pkg;
    }

    /** Latest notification time in the group (for ordering groups). */
    public long latestTime() {
        long t = 0;
        for (NotifData d : items) {
            if (d != null && d.postTime > t) {
                t = d.postTime;
            }
        }
        return t;
    }


    public NotifData latest() {
        return items.isEmpty() ? null : items.get(0);
    }

    public boolean hasKey(String key) {
        if (key == null) {
            return false;
        }
        for (NotifData d : items) {
            if (d != null && key.equals(d.key)) {
                return true;
            }
        }
        return false;
    }

    /** Remove the item with this key, if present. Used for music
     * updates: a music notification keeps the same key across song
     * changes, so the new song replaces the old one in place. */
    public void removeByKey(String key) {
        if (key == null) {
            return;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            NotifData d = items.get(i);
            if (d != null && key.equals(d.key)) {
                items.remove(i);
            }
        }
    }

    /** Display order: pinned notifications first (by pin time,
     * earliest pinned on top), then unpinned ones (newest first).
     * A new notification is always unpinned, so it goes after the
     * pinned ones, by time. */
    public void sortItems() {
        java.util.Collections.sort(items,
                new java.util.Comparator<NotifData>() {
                    @Override
                    public int compare(NotifData a, NotifData b) {
                        Long pa = a == null ? null : pinnedAtByKey.get(a.key);
                        Long pb = b == null ? null : pinnedAtByKey.get(b.key);
                        if (pa != null && pb != null) {
                            return Long.compare(pa, pb);
                        }
                        if (pa != null) {
                            return -1;
                        }
                        if (pb != null) {
                            return 1;
                        }
                        long ta = a == null ? 0 : a.postTime;
                        long tb = b == null ? 0 : b.postTime;
                        return Long.compare(tb, ta);
                    }
                });
    }

    /** Insert in display order (see sortItems). */
    public void add(NotifData data) {
        sortItems();
        int i = 0;
        while (i < items.size()) {
            NotifData it = items.get(i);
            Long pi = it == null ? null : pinnedAtByKey.get(it.key);
            Long pd = data == null ? null : pinnedAtByKey.get(data.key);
            if (pd != null) {
                // New item is pinned: among pinned ones, by pin time.
                if (pi == null || pi > pd) {
                    break;
                }
            } else {
                // New item is unpinned: after all pinned ones, then by time.
                if (pi == null && it != null
                        && it.postTime <= data.postTime) {
                    break;
                }
            }
            i++;
        }
        items.add(i, data);
    }

    public int size() {
        return items.size();
    }
}
