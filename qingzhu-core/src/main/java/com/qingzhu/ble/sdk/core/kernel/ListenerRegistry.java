// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.Subscription;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/** 监听者注册表：owner 以弱引用持有，被回收后不再回调；也可通过 Subscription 提前取消。 */
final class ListenerRegistry<T> {

    interface Visitor<T> {
        void visit(T listener);
    }

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    Subscription add(Object owner, T listener) {
        Entry e = new Entry(owner, listener);
        entries.add(e);
        return e;
    }

    void forEach(Visitor<T> visitor) {
        for (Entry e : entries) {
            if (!e.isActive()) {
                entries.remove(e);
                continue;
            }
            visitor.visit(e.listener);
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    void clear() {
        for (Entry e : entries) {
            e.active = false;
        }
        entries.clear();
    }

    private final class Entry implements Subscription {
        final T listener;
        final WeakReference<Object> owner;
        final boolean hasOwner;
        volatile boolean active = true;

        Entry(Object owner, T listener) {
            this.listener = listener;
            this.hasOwner = owner != null;
            this.owner = new WeakReference<>(owner);
        }

        @Override
        public void cancel() {
            active = false;
            entries.remove(this);
        }

        @Override
        public boolean isActive() {
            return active && (!hasOwner || owner.get() != null);
        }
    }
}
