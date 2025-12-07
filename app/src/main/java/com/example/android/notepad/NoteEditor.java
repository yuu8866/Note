package com.example.android.notepad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoteEditor extends Activity {

    private static final String TAG = "NoteEditor";

    // 更新 PROJECTION 包含分类字段
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE,
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
            NotePad.Notes.COLUMN_NAME_CREATE_DATE
    };

    private static final String ORIGINAL_CONTENT = "origContent";
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private EditText mText;
    private EditText mTitleText;
    private String mOriginalContent;

    // 新增变量
    private Spinner mCategorySpinner;
    private TextView mTimeText;
    private TextView mWordCountText;
    private LinearLayout mSearchLayout;
    private EditText mSearchBox;
    private String mCurrentCategory = "未分类";
    private Set<String> mCategories = new HashSet<>();

    // 修复1: 继承 AppCompatEditText 替代 EditText
    public static class LinedEditText extends AppCompatEditText {
        private Rect mRect;
        private Paint mPaint;

        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            setInputType(EditorInfo.TYPE_CLASS_TEXT |
                    EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE |
                    EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES);
            setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0xFFCCCCCC);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int count = getLineCount();
            Rect r = mRect;
            Paint paint = mPaint;

            for (int i = 0; i < count; i++) {
                int baseline = getLineBounds(i, r);
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }
            super.onDraw(canvas);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_editor);

        // 初始化视图
        mTitleText = findViewById(R.id.title);
        mText = findViewById(R.id.note);
        mCategorySpinner = findViewById(R.id.spinner_category);
        mTimeText = findViewById(R.id.tv_time);
        mWordCountText = findViewById(R.id.tv_word_count);
        mSearchLayout = findViewById(R.id.search_layout);
        mSearchBox = findViewById(R.id.search_box);

        // 设置按钮点击事件
        setupButtons();

        // 初始化分类
        initCategories();

        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (Intent.ACTION_EDIT.equals(action)) {
            mState = STATE_EDIT;
            mUri = intent.getData();
        } else if (Intent.ACTION_INSERT.equals(action) || Intent.ACTION_PASTE.equals(action)) {
            mState = STATE_INSERT;
            mUri = getContentResolver().insert(intent.getData(), null);

            if (mUri == null) {
                Log.e(TAG, "Failed to insert new note");
                finish();
                return;
            }
            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));
        } else {
            Log.e(TAG, "Unknown action, exiting");
            finish();
            return;
        }

        mCursor = managedQuery(mUri, PROJECTION, null, null, null);

        // 修复2: 添加 performPaste 方法的调用检查
        if (Intent.ACTION_PASTE.equals(action) && mCursor != null) {
            performPaste();
            mState = STATE_EDIT;
        }

        // 设置文本变化监听
        mText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateWordCount();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        mTitleText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateWordCount();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupButtons() {
        ImageButton btnBack = findViewById(R.id.btn_back);
        ImageButton btnSave = findViewById(R.id.btn_save);
        ImageButton btnSearch = findViewById(R.id.btn_search);
        ImageButton btnDelete = findViewById(R.id.btn_delete);
        Button btnNewCategory = findViewById(R.id.btn_new_category);
        Button btnSearchContent = findViewById(R.id.btn_search_content);
        Button btnCloseSearch = findViewById(R.id.btn_close_search);

        btnBack.setOnClickListener(v -> finish());

        btnSave.setOnClickListener(v -> {
            saveNote();
            Toast.makeText(NoteEditor.this, "保存成功", Toast.LENGTH_SHORT).show();
        });

        btnSearch.setOnClickListener(v -> {
            mSearchLayout.setVisibility(mSearchLayout.getVisibility() == View.VISIBLE ?
                    View.GONE : View.VISIBLE);
        });

        btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(NoteEditor.this)
                    .setTitle("删除笔记")
                    .setMessage("确定要删除这条笔记吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        deleteNote();
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnNewCategory.setOnClickListener(v -> showNewCategoryDialog());

        btnSearchContent.setOnClickListener(v -> searchInContent());

        btnCloseSearch.setOnClickListener(v -> {
            mSearchLayout.setVisibility(View.GONE);
            mSearchBox.setText("");
            clearHighlights();
        });
    }

    private void initCategories() {
        mCategories.add("工作");
        mCategories.add("学习");
        mCategories.add("生活");
        mCategories.add("其他");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new ArrayList<>(mCategories));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(adapter);

        mCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mCurrentCategory = (String) parent.getItemAtPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mCurrentCategory = "其他showNewCategoryDialog";
            }
        });
    }

    private void showNewCategoryDialog() {
        EditText input = new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newCategory = input.getText().toString().trim();
                    if (!newCategory.isEmpty() && !mCategories.contains(newCategory)) {
                        mCategories.add(newCategory);
                        initCategories(); // 重新初始化分类列表
                        mCategorySpinner.setSelection(((ArrayAdapter<String>)mCategorySpinner.getAdapter())
                                .getPosition(newCategory));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateWordCount() {
        String title = mTitleText.getText().toString();
        String content = mText.getText().toString();
        int count = title.length() + content.length();
        mWordCountText.setText("共" + count + "字");
    }

    private void updateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String time = "上次保存: " + sdf.format(new Date());
        mTimeText.setText(time);
    }

    private void searchInContent() {
        String searchText = mSearchBox.getText().toString().trim();
        if (searchText.isEmpty()) {
            Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show();
            return;
        }

        String content = mText.getText().toString();
        Spanned highlighted = highlightText(content, searchText);
        mText.setText(highlighted);
        Toast.makeText(this, "找到匹配项", Toast.LENGTH_SHORT).show();
    }

    private Spanned highlightText(String text, String searchText) {
        if (searchText.isEmpty()) return Html.fromHtml(text);

        // 修复：安全的正则转义
        String escapedQuery = Pattern.quote(searchText);
        String highlighted = text.replaceAll(
                "(?i)(" + escapedQuery + ")",
                "<font color='#FF0000'><b>$1</b></font>"
        );
        return Html.fromHtml(highlighted, Html.FROM_HTML_MODE_LEGACY);
    }

    private void clearHighlights() {
        String text = mText.getText().toString();
        // 移除HTML标签
        String plainText = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString();
        mText.setText(plainText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mCursor != null && mCursor.moveToFirst()) {
            // 修复3: 安全获取列索引
            int titleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
            int noteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);

            if (mState == STATE_EDIT) {
                if (titleIndex >= 0) {
                    String title = mCursor.getString(titleIndex);
                    mTitleText.setText(title != null ? title : "");
                }
            }

            if (noteIndex >= 0) {
                String note = mCursor.getString(noteIndex);
                mText.setText(note != null ? note : "");
            }

            updateWordCount();
            updateTime();

            if (mOriginalContent == null) {
                mOriginalContent = mText.getText().toString();
            }
        }
    }

    private void saveNote() {
        String title = mTitleText.getText().toString();
        String content = mText.getText().toString();

        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "笔记内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, content);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        // 分类保存
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCurrentCategory);

        getContentResolver().update(mUri, values, null, null);
        updateTime();
    }

    private void deleteNote() {
        if (mCursor != null) {
            getContentResolver().delete(mUri, null, null);
        }
    }

    // 修复4: 添加 performPaste 方法（从原始代码复制）
    private final void performPaste() {
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        ContentResolver cr = getContentResolver();
        ClipData clip = clipboard.getPrimaryClip();

        if (clip != null) {
            String text = null;
            String title = null;
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();

            if (uri != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(cr.getType(uri))) {
                Cursor orig = cr.query(
                        uri,
                        PROJECTION,
                        null,
                        null,
                        null
                );

                if (orig != null && orig.moveToFirst()) {
                    int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                    int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);

                    if (colNoteIndex >= 0) text = orig.getString(colNoteIndex);
                    if (colTitleIndex >= 0) title = orig.getString(colTitleIndex);
                    orig.close();
                }
            }

            if (text == null) {
                text = item.coerceToText(this).toString();
            }

            updateNote(text, title);
        }
    }

    // 修复5: 添加 updateNote 方法（从原始代码复制）
    private final void updateNote(String text, String title) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        if (mState == STATE_INSERT) {
            if (title == null) {
                int length = text.length();
                title = text.substring(0, Math.min(30, length));
                if (length > 30) {
                    int lastSpace = title.lastIndexOf(' ');
                    if (lastSpace > 0) {
                        title = title.substring(0, lastSpace);
                    }
                }
            }
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        } else if (title != null) {
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        }

        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);

        getContentResolver().update(
                mUri,
                values,
                null,
                null
        );
    }

    // 修复6: 添加必要的菜单方法（如果需要）
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.editor_options_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            String text = mText.getText().toString();
            updateNote(text, null);
            finish();
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    // 修复7: 添加 onSaveInstanceState 和 onPause 方法（如果需要）
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mCursor != null) {
            String text = mText.getText().toString();
            int length = text.length();

            if (isFinishing() && length == 0) {
                setResult(RESULT_CANCELED);
                deleteNote();
            } else if (mState == STATE_EDIT) {
                updateNote(text, null);
            } else if (mState == STATE_INSERT) {
                updateNote(text, text);
                mState = STATE_EDIT;
            }
        }
    }
}