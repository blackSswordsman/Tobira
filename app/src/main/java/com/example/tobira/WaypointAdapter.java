package com.example.tobira;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter for the inline route editor.
 * Alternates between TYPE_WAYPOINT rows and TYPE_ADD (insert +) rows.
 * Layout positions: 0=wp0, 1=add0, 2=wp1, 3=add1 ... 2n=wpN, 2n+1=add(trailing)
 */
public class WaypointAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onRemove(int waypointIndex);
        void onAddBetween(int afterWaypointIndex);
        void onMoved(int fromIndex, int toIndex);
        void onWaypointTap(int waypointIndex);
    }

    private static final int TYPE_WAYPOINT = 0;
    private static final int TYPE_ADD = 1;

    private final List<WaypointItem> items = new ArrayList<>();
    private final Listener listener;
    private ItemTouchHelper touchHelper;

    public WaypointAdapter(Listener listener) {
        this.listener = listener;
    }

    public void attachTouchHelper(RecyclerView rv) {
        touchHelper = new ItemTouchHelper(new DragCallback());
        touchHelper.attachToRecyclerView(rv);
    }

    public void setWaypoints(List<WaypointItem> waypoints) {
        items.clear();
        items.addAll(waypoints);
        notifyDataSetChanged();
    }

    public List<WaypointItem> getWaypoints() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public int getItemViewType(int position) {
        if (items.isEmpty()) return TYPE_ADD;
        return (position % 2 == 0) ? TYPE_WAYPOINT : TYPE_ADD;
    }

    @Override
    public int getItemCount() {
        // N waypoints interleaved with N-1 add rows, plus 1 trailing add = N*2
        // positions: 0→wp0, 1→add(0→1), 2→wp1, 3→add(1→2), ..., 2n-2→wpN, 2n-1→addTrailing
        return items.isEmpty() ? 1 : items.size() * 2;
    }

    private int waypointIndexOf(int position) {
        return position / 2;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_WAYPOINT) {
            return new WaypointVH(inf.inflate(R.layout.item_waypoint, parent, false));
        } else {
            return new AddVH(inf.inflate(R.layout.item_add_waypoint, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_WAYPOINT) {
            int idx = waypointIndexOf(position);
            WaypointItem item = items.get(idx);
            WaypointVH vh = (WaypointVH) holder;
            vh.name.setText(item.name.isEmpty()
                    ? holder.itemView.getContext().getString(R.string.unset_waypoint) : item.name);
            vh.name.setAlpha(item.name.isEmpty() ? 0.5f : 1f);
            // 地点名タップで検索パネルを開く
            vh.name.setOnClickListener(v -> {
                int i = holder.getAdapterPosition();
                if (i != RecyclerView.NO_ID) listener.onWaypointTap(waypointIndexOf(i));
            });
            // ↑↓ボタンタッチでドラッグ開始
            vh.dragBtn.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                    touchHelper.startDrag(holder);
                }
                return false;
            });
            // 先頭行は上の縦線を非表示
            vh.lineTop.setVisibility(idx == 0 ? View.INVISIBLE : View.VISIBLE);
            vh.lineBottom.setVisibility(View.VISIBLE);
        } else {
            // addIdx: position 1→after wp0, 3→after wp1, ... (position-1)/2
            int afterIdx = (position - 1) / 2;
            AddVH vh = (AddVH) holder;
            if (vh.segmentInfo != null) vh.segmentInfo.setText("");
            vh.addBtn.setOnClickListener(v -> listener.onAddBetween(afterIdx));
        }
    }

    // --- ViewHolders ---

    static class WaypointVH extends RecyclerView.ViewHolder {
        final View lineTop;
        final View lineBottom;
        final TextView name;
        final ImageButton dragBtn;

        WaypointVH(View v) {
            super(v);
            lineTop = v.findViewById(R.id.line_top);
            lineBottom = v.findViewById(R.id.line_bottom);
            name = v.findViewById(R.id.waypoint_name);
            dragBtn = v.findViewById(R.id.remove_waypoint);
        }
    }

    static class AddVH extends RecyclerView.ViewHolder {
        final TextView segmentInfo;
        final ImageButton addBtn;

        AddVH(View v) {
            super(v);
            segmentInfo = v.findViewById(R.id.segment_info);
            addBtn = v.findViewById(R.id.add_waypoint_btn);
        }
    }

    // --- Drag & Drop ---

    private class DragCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            if (getItemViewType(vh.getAdapterPosition()) == TYPE_ADD) return 0;
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                              @NonNull RecyclerView.ViewHolder to) {
            int fp = from.getAdapterPosition();
            int tp = to.getAdapterPosition();
            if (getItemViewType(fp) != TYPE_WAYPOINT || getItemViewType(tp) != TYPE_WAYPOINT) {
                return false;
            }
            int fi = waypointIndexOf(fp);
            int ti = waypointIndexOf(tp);
            Collections.swap(items, fi, ti);
            notifyItemMoved(fp, tp);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) { }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            // Notify full dataset to fix add-row positions after drop
            notifyDataSetChanged();
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // drag only via handle
        }
    }

    // --- Data model ---

    public static class WaypointItem {
        public final String label;
        public final String name;
        public final boolean removable;
        public Place place; // null = unset

        public WaypointItem(String label, String name, boolean removable, Place place) {
            this.label = label;
            this.name = name;
            this.removable = removable;
            this.place = place;
        }
    }
}
