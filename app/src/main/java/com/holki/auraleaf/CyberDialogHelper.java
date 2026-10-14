package com.holki.auraleaf;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

public class CyberDialogHelper {

    public interface OnInputCallback {
        void onConfirmed(String text);
    }

    public interface OnFolderSelectedCallback {
        void onSelected(long groupId);
    }

    public interface OnThemeSelectedCallback {
        void onSelected(int themeId);
    }

    private static Dialog createBaseDialog(Activity activity, View contentView) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(contentView);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
        return dialog;
    }

    public static void showInputDialog(Activity activity, String tag, String title,
                                      String initialText, String hint, String confirmText,
                                      OnInputCallback callback) {
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_cyber_input, null);
        Dialog dialog = createBaseDialog(activity, v);

        TextView tvTag = v.findViewById(R.id.tv_dialog_tag);
        TextView tvTitle = v.findViewById(R.id.tv_dialog_title);
        EditText etInput = v.findViewById(R.id.et_dialog_input);
        Button btnCancel = v.findViewById(R.id.btn_dialog_cancel);
        Button btnConfirm = v.findViewById(R.id.btn_dialog_confirm);

        if (tag != null) tvTag.setText(tag);
        if (title != null) tvTitle.setText(title);
        if (hint != null) etInput.setHint(hint);
        if (initialText != null) {
            etInput.setText(initialText);
            etInput.setSelection(initialText.length());
        }
        if (confirmText != null) btnConfirm.setText(confirmText);

        btnCancel.setOnClickListener(b -> dialog.dismiss());
        btnConfirm.setOnClickListener(b -> {
            String txt = etInput.getText().toString().trim();
            if (!txt.isEmpty()) {
                dialog.dismiss();
                if (callback != null) callback.onConfirmed(txt);
            }
        });

        dialog.show();
    }

    public static void showConfirmDialog(Activity activity, String tag, String title,
                                         String message, String confirmText, Runnable onConfirm) {
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_cyber_confirm, null);
        Dialog dialog = createBaseDialog(activity, v);

        TextView tvTag = v.findViewById(R.id.tv_dialog_tag);
        TextView tvTitle = v.findViewById(R.id.tv_dialog_title);
        TextView tvMsg = v.findViewById(R.id.tv_dialog_message);
        Button btnCancel = v.findViewById(R.id.btn_dialog_cancel);
        Button btnConfirm = v.findViewById(R.id.btn_dialog_confirm);

        if (tag != null) tvTag.setText(tag);
        if (title != null) tvTitle.setText(title);
        if (message != null) tvMsg.setText(message);
        if (confirmText != null) btnConfirm.setText(confirmText);

        btnCancel.setOnClickListener(b -> dialog.dismiss());
        btnConfirm.setOnClickListener(b -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });

        dialog.show();
    }

    public static void showFolderDeleteOptions(Activity activity, String folderTitle,
                                               Runnable onDeleteFolderOnly, Runnable onDeleteAll) {
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_cyber_folder_options, null);
        Dialog dialog = createBaseDialog(activity, v);

        TextView tvTitle = v.findViewById(R.id.tv_folder_del_title);
        if (folderTitle != null) {
            tvTitle.setText("УДАЛЕНИЕ: " + folderTitle.toUpperCase());
        }

        View btnOnly = v.findViewById(R.id.btn_delete_folder_only);
        View btnAll = v.findViewById(R.id.btn_delete_folder_all);
        Button btnCancel = v.findViewById(R.id.btn_folder_del_cancel);

        btnOnly.setOnClickListener(b -> {
            dialog.dismiss();
            if (onDeleteFolderOnly != null) onDeleteFolderOnly.run();
        });

        btnAll.setOnClickListener(b -> {
            dialog.dismiss();
            if (onDeleteAll != null) onDeleteAll.run();
        });

        btnCancel.setOnClickListener(b -> dialog.dismiss());
        dialog.show();
    }

    public static void showMoveToFolderDialog(Activity activity, BookItem book, List<GroupItem> groups,
                                             OnFolderSelectedCallback onSelect, Runnable onCreateNew) {
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_cyber_move_folder, null);
        Dialog dialog = createBaseDialog(activity, v);

        TextView tvSub = v.findViewById(R.id.tv_move_subtitle);
        tvSub.setText(book.title);

        LinearLayout layoutList = v.findViewById(R.id.layout_folder_list);
        Button btnCreateNew = v.findViewById(R.id.btn_move_create_new);
        Button btnCancel = v.findViewById(R.id.btn_move_cancel);

        // 1. Root Shelf option
        addFolderRow(activity, layoutList, "ГЛАВНАЯ ПОЛКА", "Корневой каталог (без папки)", book.groupId == 0, () -> {
            dialog.dismiss();
            if (onSelect != null) onSelect.onSelected(0L);
        });

        // 2. Existing folders
        if (groups != null) {
            for (GroupItem g : groups) {
                String sub = g.bookCount + " файлов";
                boolean isCurrent = (book.groupId == g.id);
                addFolderRow(activity, layoutList, g.title.toUpperCase(), sub, isCurrent, () -> {
                    dialog.dismiss();
                    if (onSelect != null) onSelect.onSelected(g.id);
                });
            }
        }

        btnCreateNew.setOnClickListener(b -> {
            dialog.dismiss();
            if (onCreateNew != null) onCreateNew.run();
        });

        btnCancel.setOnClickListener(b -> dialog.dismiss());
        dialog.show();
    }

    private static void addFolderRow(Activity act, LinearLayout container, String title, String subtitle,
                                     boolean isSelected, Runnable onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_cyber_card_item);
        row.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(act, 8);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(act);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setColorFilter(isSelected ? 0xFF38BDF8 : 0xFF64748B);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(act, 20), dp(act, 20));
        icon.setLayoutParams(icLp);
        row.addView(icon);

        LinearLayout textLayout = new LinearLayout(act);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(act, 12);
        textLayout.setLayoutParams(tLp);

        TextView tvT = new TextView(act);
        tvT.setText(title);
        tvT.setTextColor(isSelected ? 0xFF38BDF8 : 0xFFF8FAFC);
        tvT.setTextSize(13f);
        tvT.setTypeface(null, android.graphics.Typeface.BOLD);
        textLayout.addView(tvT);

        TextView tvS = new TextView(act);
        tvS.setText(subtitle);
        tvS.setTextColor(0xFF94A3B8);
        tvS.setTextSize(11f);
        textLayout.addView(tvS);

        row.addView(textLayout);

        if (isSelected) {
            TextView badge = new TextView(act);
            badge.setText("ТЕКУЩАЯ");
            badge.setTextColor(0xFF38BDF8);
            badge.setTextSize(10f);
            badge.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(badge);
        }

        row.setOnClickListener(v -> onClick.run());
        container.addView(row);
    }

    public static void showThemeSelector(Activity activity, int currentThemeId, OnThemeSelectedCallback callback) {
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_cyber_themes, null);
        Dialog dialog = createBaseDialog(activity, v);

        View cardStudio = v.findViewById(R.id.card_theme_studio);
        View cardSepia = v.findViewById(R.id.card_theme_sepia);
        View cardOled = v.findViewById(R.id.card_theme_oled);
        View cardForest = v.findViewById(R.id.card_theme_forest);

        TextView indStudio = v.findViewById(R.id.indicator_studio);
        TextView indSepia = v.findViewById(R.id.indicator_sepia);
        TextView indOled = v.findViewById(R.id.indicator_oled);
        TextView indForest = v.findViewById(R.id.indicator_forest);

        indStudio.setVisibility(currentThemeId == ThemeManager.THEME_STUDIO ? View.VISIBLE : View.GONE);
        indSepia.setVisibility(currentThemeId == ThemeManager.THEME_SEPIA ? View.VISIBLE : View.GONE);
        indOled.setVisibility(currentThemeId == ThemeManager.THEME_OLED ? View.VISIBLE : View.GONE);
        indForest.setVisibility(currentThemeId == ThemeManager.THEME_FOREST ? View.VISIBLE : View.GONE);

        cardStudio.setOnClickListener(b -> {
            dialog.dismiss();
            if (callback != null) callback.onSelected(ThemeManager.THEME_STUDIO);
        });

        cardSepia.setOnClickListener(b -> {
            dialog.dismiss();
            if (callback != null) callback.onSelected(ThemeManager.THEME_SEPIA);
        });

        cardOled.setOnClickListener(b -> {
            dialog.dismiss();
            if (callback != null) callback.onSelected(ThemeManager.THEME_OLED);
        });

        cardForest.setOnClickListener(b -> {
            dialog.dismiss();
            if (callback != null) callback.onSelected(ThemeManager.THEME_FOREST);
        });

        v.findViewById(R.id.btn_theme_close).setOnClickListener(b -> dialog.dismiss());
        dialog.show();
    }

    private static int dp(Activity act, int val) {
        return Math.round(val * act.getResources().getDisplayMetrics().density);
    }
}
