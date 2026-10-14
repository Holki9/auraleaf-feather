package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnLibraryItemClickListener {
        void onBookClick(BookItem book);
        void onBookRename(BookItem book);
        void onBookDelete(BookItem book);
        void onBookMove(BookItem book);
        void onGroupClick(GroupItem group);
        void onGroupRename(GroupItem group);
        void onGroupDelete(GroupItem group);
    }

    private final Context context;
    private final OnLibraryItemClickListener listener;
    private List<LibraryItem> items = new ArrayList<>();

    public BookAdapter(Context context, OnLibraryItemClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<LibraryItem> list) {
        this.items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == LibraryItem.TYPE_GROUP) {
            View v = LayoutInflater.from(context).inflate(R.layout.item_group, parent, false);
            return new GroupViewHolder(v);
        } else {
            View v = LayoutInflater.from(context).inflate(R.layout.item_book, parent, false);
            return new BookViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        LibraryItem item = items.get(position);
        if (holder instanceof GroupViewHolder && item instanceof GroupItem) {
            ((GroupViewHolder) holder).bind((GroupItem) item, listener, context);
        } else if (holder instanceof BookViewHolder && item instanceof BookItem) {
            ((BookViewHolder) holder).bind((BookItem) item, listener, context);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        GridLayout layoutCollage;
        ImageView imgC1, imgC2, imgC3, imgC4;
        ImageView imgEmptyFolder;
        TextView tvGroupTitle;
        TextView tvGroupCount;
        ImageView btnGroupMore;

        public GroupViewHolder(@NonNull View itemView) {
            super(itemView);
            layoutCollage = itemView.findViewById(R.id.layout_collage);
            imgC1 = itemView.findViewById(R.id.img_c1);
            imgC2 = itemView.findViewById(R.id.img_c2);
            imgC3 = itemView.findViewById(R.id.img_c3);
            imgC4 = itemView.findViewById(R.id.img_c4);
            imgEmptyFolder = itemView.findViewById(R.id.img_empty_folder);
            tvGroupTitle = itemView.findViewById(R.id.tv_group_title);
            tvGroupCount = itemView.findViewById(R.id.tv_group_count);
            btnGroupMore = itemView.findViewById(R.id.btn_group_more);
        }

        public void bind(GroupItem group, OnLibraryItemClickListener listener, Context ctx) {
            tvGroupTitle.setText(group.title);
            tvGroupCount.setText(formatCount(group.bookCount));

            if (group.coverPaths == null || group.coverPaths.isEmpty()) {
                layoutCollage.setVisibility(View.GONE);
                imgEmptyFolder.setVisibility(View.VISIBLE);
            } else {
                layoutCollage.setVisibility(View.VISIBLE);
                imgEmptyFolder.setVisibility(View.GONE);
                setSlotCover(imgC1, 0, group.coverPaths);
                setSlotCover(imgC2, 1, group.coverPaths);
                setSlotCover(imgC3, 2, group.coverPaths);
                setSlotCover(imgC4, 3, group.coverPaths);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onGroupClick(group);
            });

            btnGroupMore.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(ctx, btnGroupMore);
                popup.getMenu().add(0, 1, 0, "Переименовать");
                popup.getMenu().add(0, 2, 1, "Удалить папку");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1 && listener != null) {
                        listener.onGroupRename(group);
                        return true;
                    } else if (item.getItemId() == 2 && listener != null) {
                        listener.onGroupDelete(group);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }

        private void setSlotCover(ImageView img, int index, List<String> covers) {
            if (index < covers.size()) {
                String path = covers.get(index);
                if (path != null && new File(path).exists()) {
                    Bitmap bmp = BitmapFactory.decodeFile(path);
                    if (bmp != null) {
                        img.setImageBitmap(bmp);
                        img.setAlpha(1.0f);
                        return;
                    }
                }
            }
            img.setImageDrawable(null);
            img.setAlpha(0.2f);
        }

        private String formatCount(int count) {
            int rem10 = count % 10;
            int rem100 = count % 100;
            if (rem10 == 1 && rem100 != 11) {
                return count + " книга";
            } else if (rem10 >= 2 && rem10 <= 4 && (rem100 < 10 || rem100 >= 20)) {
                return count + " книги";
            } else {
                return count + " книг";
            }
        }
    }

    static class BookViewHolder extends RecyclerView.ViewHolder {
        ImageView imgCover;
        TextView tvBadgePercent;
        TextView tvTitle;
        ProgressBar progressBar;
        TextView tvPageInfo;
        ImageView btnMore;

        public BookViewHolder(@NonNull View itemView) {
            super(itemView);
            imgCover = itemView.findViewById(R.id.img_cover);
            tvBadgePercent = itemView.findViewById(R.id.tv_badge_percent);
            tvTitle = itemView.findViewById(R.id.tv_title);
            progressBar = itemView.findViewById(R.id.progress_bar);
            tvPageInfo = itemView.findViewById(R.id.tv_page_info);
            btnMore = itemView.findViewById(R.id.btn_more);
        }

        public void bind(BookItem book, OnLibraryItemClickListener listener, Context ctx) {
            tvTitle.setText(book.title);

            int pct = book.totalPages > 0 ? (int) (((book.lastPage + 1) / (float) book.totalPages) * 100) : 0;
            tvBadgePercent.setText(pct + "%");
            progressBar.setProgress(pct);
            tvPageInfo.setText("Стр. " + (book.lastPage + 1) + " / " + book.totalPages);

            if (book.coverPath != null && new File(book.coverPath).exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(book.coverPath);
                imgCover.setImageBitmap(bmp);
            } else {
                imgCover.setImageResource(R.drawable.ic_shelf);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onBookClick(book);
            });

            btnMore.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(ctx, btnMore);
                popup.getMenu().add(0, 1, 0, "Переместить в папку");
                popup.getMenu().add(0, 2, 1, "Переименовать");
                popup.getMenu().add(0, 3, 2, "Удалить");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1 && listener != null) {
                        listener.onBookMove(book);
                        return true;
                    } else if (item.getItemId() == 2 && listener != null) {
                        listener.onBookRename(book);
                        return true;
                    } else if (item.getItemId() == 3 && listener != null) {
                        listener.onBookDelete(book);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }
}
