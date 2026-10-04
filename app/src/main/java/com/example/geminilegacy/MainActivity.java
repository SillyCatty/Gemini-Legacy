package com.example.geminilegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.v4.content.FileProvider;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.ViewDragHelper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int MAX_SEND_DIM = 1024;

    private static final int MENU_NEW = 1;
    private static final int MENU_ADD_IMAGE = 2;
    private static final int MENU_PHOTO = 3;
    private static final int MENU_REGEN = 4;
    private static final int MENU_COPY_LAST = 5;
    private static final int MENU_HISTORY = 6;
    private static final int MENU_SETTINGS = 7;
    private static final int MENU_UPDATE = 8;

    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();
    private final List<ChatStore.Summary> allChats = new ArrayList<ChatStore.Summary>();
    private final List<ChatStore.Summary> chats = new ArrayList<ChatStore.Summary>();   // after the search filter
    private String searchQuery = "";
    private ChatAdapter adapter;
    private ChatListAdapter chatListAdapter;
    private DrawerLayout drawer;
    private EditText etMessage;
    private Button btnSend;
    private TextView tvPrefix;
    private TextView tvModel;
    private View attachRow;
    private ImageView ivAttach;
    private PopupWindow modelPopup;

    private String currentChatId;
    private byte[] pendingImage;
    private File cameraFile;
    private boolean sending;
    private GeminiClient.Handle currentCall;
    private int maxBubbleWidth;
    private int layoutGen;   // bumped on rotation so cached views are rebuilt for the new width
    private float density;

    // Drives the animated "Thinking..." dots while a reply is pending.
    private final Handler uiHandler = new Handler();
    private int dotPhase;
    private final Runnable dotsTick = new Runnable() {
        @Override public void run() {
            dotPhase = (dotPhase + 1) % 4;
            adapter.notifyDataSetChanged();
            uiHandler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        maxBubbleWidth = (int) (dm.widthPixels * 0.82f);

        drawer = (DrawerLayout) findViewById(R.id.drawer);
        etMessage = (EditText) findViewById(R.id.etMessage);
        btnSend = (Button) findViewById(R.id.btnSend);
        tvPrefix = (TextView) findViewById(R.id.tvPrefix);
        tvModel = (TextView) findViewById(R.id.tvModel);
        attachRow = findViewById(R.id.attachRow);
        ivAttach = (ImageView) findViewById(R.id.ivAttach);

        ListView list = (ListView) findViewById(R.id.list);
        adapter = new ChatAdapter();
        list.setAdapter(adapter);

        setupDrawer();
        refreshTitle();
        UpdateFlow.checkOnStartup(this);

        findViewById(R.id.btnMenu).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { drawer.openDrawer(Gravity.LEFT); }
        });
        tvModel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showModelPopup(); }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sending) stopReply(); else send();
            }
        });
        findViewById(R.id.btnAttach).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        findViewById(R.id.btnRemoveAttach).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearAttachment(); }
        });

        // After the system kills and restarts the app, reopen the chat that was on screen.
        if (savedInstanceState != null) {
            String id = savedInstanceState.getString("chatId");
            if (id != null) openChat(id);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentChatId != null) outState.putString("chatId", currentChatId);
    }

    /** Rotation lands here (see configChanges in the manifest): keep the chat, just relayout. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        maxBubbleWidth = (int) (dm.widthPixels * 0.82f);
        layoutGen++;
        for (ChatMessage m : messages) m.rendered = null;
        if (modelPopup != null && modelPopup.isShowing()) modelPopup.dismiss();
        widenDrawerEdge();
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Settings.getApiKey(this).length() == 0) {
            Toast.makeText(this, "Add your Gemini API key in Settings", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.LEFT)) {
            drawer.closeDrawer(Gravity.LEFT);
        } else {
            super.onBackPressed();
        }
    }

    // ---- hardware Menu button ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_NEW, 0, "New chat");
        menu.add(0, MENU_ADD_IMAGE, 1, "Add image");
        menu.add(0, MENU_PHOTO, 2, "Take photo");
        menu.add(0, MENU_REGEN, 3, "Regenerate reply");
        menu.add(0, MENU_COPY_LAST, 4, "Copy last reply");
        menu.add(0, MENU_HISTORY, 5, "Chat history");
        menu.add(0, MENU_SETTINGS, 6, "Settings");
        menu.add(0, MENU_UPDATE, 7, "Check for updates");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem regen = menu.findItem(MENU_REGEN);
        regen.setTitle(sending ? "Stop reply" : "Regenerate reply");
        regen.setEnabled(sending || lastUserIndex() >= 0);
        menu.findItem(MENU_COPY_LAST).setEnabled(lastReplyText() != null);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_NEW:
                if (!busyToast()) startNewChat();
                return true;
            case MENU_ADD_IMAGE:
                pickImage();
                return true;
            case MENU_PHOTO:
                takePhoto();
                return true;
            case MENU_REGEN:
                if (sending) stopReply(); else regenerate();
                return true;
            case MENU_COPY_LAST:
                String last = lastReplyText();
                if (last != null) copyText(last);
                return true;
            case MENU_HISTORY:
                drawer.openDrawer(Gravity.LEFT);
                return true;
            case MENU_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case MENU_UPDATE:
                UpdateFlow.check(this, false);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ---- title and model dropdown ----

    /** "Gemini Legacy Pro" normally, just "Pro Extended" when extended thinking is on. */
    private void refreshTitle() {
        String label = Settings.tierLabel(Settings.getTier(this));
        boolean extended = Settings.isExtended(this);
        tvPrefix.setVisibility(extended ? View.GONE : View.VISIBLE);
        tvModel.setText(extended ? label + " Extended" : label);
    }

    private void showModelPopup() {
        if (modelPopup != null && modelPopup.isShowing()) {
            modelPopup.dismiss();
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.popup_model, null);
        RadioGroup rg = (RadioGroup) content.findViewById(R.id.rgModel);
        Switch sw = (Switch) content.findViewById(R.id.swExtended);

        int tier = Settings.getTier(this);
        rg.check(tier == Settings.TIER_PRO ? R.id.rbPro
                : tier == Settings.TIER_FLASH ? R.id.rbFlash : R.id.rbLite);
        sw.setChecked(Settings.isExtended(this));

        modelPopup = new PopupWindow(content, (int) (240 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        modelPopup.setBackgroundDrawable(new ColorDrawable(0xFF2D2D2D));
        modelPopup.setOutsideTouchable(true);

        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int checkedId) {
                int t = checkedId == R.id.rbPro ? Settings.TIER_PRO
                        : checkedId == R.id.rbFlash ? Settings.TIER_FLASH : Settings.TIER_LITE;
                Settings.setTier(MainActivity.this, t);
                refreshTitle();
                modelPopup.dismiss();
            }
        });
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Settings.setExtended(MainActivity.this, checked);
                refreshTitle();
            }
        });
        modelPopup.showAsDropDown(tvModel);
    }

    // ---- drawer (swipe in from the left edge, or tap the hamburger) ----

    private void setupDrawer() {
        widenDrawerEdge();

        ListView chatList = (ListView) findViewById(R.id.chatList);
        chatListAdapter = new ChatListAdapter();
        chatList.setAdapter(chatListAdapter);
        chatList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int position, long id) {
                openChat(chats.get(position).id);
            }
        });
        chatList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int position, long id) {
                showChatActions(chats.get(position));
                return true;
            }
        });

        ((EditText) findViewById(R.id.etSearchChats)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase();
                applyChatFilter();
            }
        });

        findViewById(R.id.btnDrawerNew).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (busyToast()) return;
                startNewChat();
                drawer.closeDrawer(Gravity.LEFT);
            }
        });
        findViewById(R.id.btnDrawerSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                drawer.closeDrawer(Gravity.LEFT);
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        drawer.setDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawerView) { refreshChatList(); }
        });
        refreshChatList();
    }

    /** DrawerLayout's default swipe-in zone is only ~20dp; make it easier to grab. */
    private void widenDrawerEdge() {
        try {
            java.lang.reflect.Field fDragger = DrawerLayout.class.getDeclaredField("mLeftDragger");
            fDragger.setAccessible(true);
            ViewDragHelper dragger = (ViewDragHelper) fDragger.get(drawer);
            java.lang.reflect.Field fEdge = ViewDragHelper.class.getDeclaredField("mEdgeSize");
            fEdge.setAccessible(true);
            fEdge.setInt(dragger, (int) (36 * density));
        } catch (Exception ignored) { }
    }

    private boolean busyToast() {
        if (sending) {
            Toast.makeText(this, "Stop or wait for Gemini to finish first", Toast.LENGTH_SHORT).show();
        }
        return sending;
    }

    private void refreshChatList() {
        allChats.clear();
        allChats.addAll(ChatStore.list(this));
        applyChatFilter();
    }

    /** Shows chats whose title or messages contain the search text. */
    private void applyChatFilter() {
        chats.clear();
        for (ChatStore.Summary s : allChats) {
            if (searchQuery.length() == 0 || s.title.toLowerCase().contains(searchQuery)
                    || s.search.contains(searchQuery)) {
                chats.add(s);
            }
        }
        chatListAdapter.notifyDataSetChanged();
    }

    private void showChatActions(final ChatStore.Summary chat) {
        new AlertDialog.Builder(this)
                .setTitle(chat.title)
                .setItems(new String[]{"Rename", "Delete"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) promptRename(chat); else confirmDelete(chat);
                    }
                })
                .show();
    }

    private void promptRename(final ChatStore.Summary chat) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(chat.title);
        input.setSelection(chat.title.length());
        new AlertDialog.Builder(this)
                .setTitle("Rename chat")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) return;
                        ChatStore.rename(MainActivity.this, chat.id, name);
                        refreshChatList();
                    }
                })
                .show();
    }

    private void startNewChat() {
        currentChatId = null;
        messages.clear();
        clearAttachment();
        adapter.notifyDataSetChanged();
    }

    private void openChat(final String id) {
        if (busyToast()) return;
        drawer.closeDrawer(Gravity.LEFT);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ChatMessage> loaded = ChatStore.load(MainActivity.this, id);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        currentChatId = id;
                        messages.clear();
                        messages.addAll(loaded);
                        clearAttachment();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final ChatStore.Summary chat) {
        new AlertDialog.Builder(this)
                .setTitle("Delete chat?")
                .setMessage(chat.title)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (chat.id.equals(currentChatId)) {
                            if (busyToast()) return;
                            startNewChat();
                        }
                        ChatStore.delete(MainActivity.this, chat.id);
                        refreshChatList();
                    }
                })
                .show();
    }

    /** Saves the current chat on a background thread. */
    private void persist() {
        if (messages.isEmpty()) return;
        if (currentChatId == null) currentChatId = String.valueOf(System.currentTimeMillis());
        String title = "Chat";
        for (ChatMessage m : messages) {
            if (m.fromUser) {
                String t = m.text.replace('\n', ' ').trim();
                title = t.length() == 0 ? "Image" : (t.length() > 40 ? t.substring(0, 40) + "..." : t);
                break;
            }
        }
        final String id = currentChatId;
        final String finalTitle = title;
        final List<ChatMessage> copy = new ArrayList<ChatMessage>(messages);
        new Thread(new Runnable() {
            @Override public void run() { ChatStore.save(MainActivity.this, id, finalTitle, copy); }
        }).start();
    }

    // ---- attachments ----

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Choose an image"), REQ_PICK_IMAGE);
    }

    private void takePhoto() {
        try {
            File dir = getExternalFilesDir("camera");
            if (dir == null) throw new Exception("No storage available");
            dir.mkdirs();
            cameraFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(cameraFile);
            }
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "Camera unavailable: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_PICK_IMAGE && data != null && data.getData() != null) {
            attachImage(data.getData(), null);
        } else if (requestCode == REQ_CAMERA && cameraFile != null && cameraFile.length() > 0) {
            attachImage(Uri.fromFile(cameraFile), cameraFile);
        }
    }

    private void attachImage(final Uri uri, final File deleteAfter) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final byte[] jpeg = ImageUtil.loadScaledJpeg(getContentResolver(), uri, MAX_SEND_DIM);
                    final Bitmap thumb = ImageUtil.decodeForDisplay(jpeg, 160);
                    if (deleteAfter != null) deleteAfter.delete();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pendingImage = jpeg;
                            ivAttach.setImageBitmap(thumb);
                            attachRow.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Could not load image: " + t.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void clearAttachment() {
        pendingImage = null;
        ivAttach.setImageBitmap(null);
        attachRow.setVisibility(View.GONE);
    }

    // ---- sending ----

    private void send() {
        if (sending) return;
        String text = etMessage.getText().toString().trim();
        if (text.length() == 0 && pendingImage == null) return;
        if (!hasApiKey()) return;

        Bitmap thumb = pendingImage == null ? null : ImageUtil.decodeForDisplay(pendingImage, 600);
        messages.add(new ChatMessage(true, text, pendingImage, "image/jpeg", thumb, false));
        etMessage.setText("");
        clearAttachment();
        adapter.notifyDataSetChanged();
        persist();
        startReply();
    }

    private boolean hasApiKey() {
        if (Settings.getApiKey(this).length() == 0) {
            startActivity(new Intent(this, SettingsActivity.class));
            return false;
        }
        return true;
    }

    /** Asks Gemini to answer the chat as it currently stands; the reply bubble fills in as chunks stream. */
    private void startReply() {
        setSending(true);
        List<ChatMessage> history = new ArrayList<ChatMessage>(messages);
        final ChatMessage reply = new ChatMessage(false, "", null, null, null, false);
        reply.pending = true;
        messages.add(reply);
        adapter.notifyDataSetChanged();
        startDots();

        currentCall = GeminiClient.generate(this, history, Settings.getTier(this), Settings.isExtended(this),
                new GeminiClient.Callback() {
                    @Override public void onProgress(String thoughts, String text) {
                        reply.thoughts = thoughts;
                        reply.text = text;
                        reply.rendered = null;
                        adapter.notifyDataSetChanged();
                    }

                    @Override public void onDone(GeminiClient.Reply r) {
                        reply.thoughts = r.thoughts;
                        reply.text = r.text;
                        reply.image = r.image;
                        reply.mimeType = r.mimeType;
                        reply.thumb = r.image == null ? null : ImageUtil.decodeForDisplay(r.image, 800);
                        reply.rendered = null;
                        reply.pending = false;
                        finishReply();
                    }

                    @Override public void onError(String message) {
                        reply.text = message;
                        reply.thoughts = "";
                        reply.error = true;
                        reply.rendered = null;
                        reply.pending = false;
                        finishReply();
                    }

                    @Override public void onCancelled(String thoughts, String text) {
                        if (text.length() == 0 && thoughts.length() == 0) {
                            messages.remove(reply);
                        } else {
                            reply.thoughts = thoughts;
                            reply.text = text.length() == 0 ? "_Stopped_" : text;
                            reply.rendered = null;
                            reply.pending = false;
                        }
                        finishReply();
                    }
                });
    }

    private void finishReply() {
        currentCall = null;
        stopDots();
        adapter.notifyDataSetChanged();
        persist();
        setSending(false);
    }

    private void stopReply() {
        if (currentCall != null) currentCall.cancel();
    }

    /** Drops the last answer (and anything after your last message) and asks again. */
    private void regenerate() {
        if (sending) return;
        int idx = lastUserIndex();
        if (idx < 0 || !hasApiKey()) return;
        messages.subList(idx + 1, messages.size()).clear();
        adapter.notifyDataSetChanged();
        startReply();
    }

    private int lastUserIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).fromUser) return i;
        }
        return -1;
    }

    private String lastReplyText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (!m.fromUser && !m.error && !m.pending && m.text.length() > 0) return m.text;
        }
        return null;
    }

    private void setSending(boolean value) {
        sending = value;
        btnSend.setText(value ? "Stop" : "Send");
        invalidateOptionsMenu();
    }

    private void startDots() {
        dotPhase = 0;
        uiHandler.removeCallbacks(dotsTick);
        uiHandler.postDelayed(dotsTick, 400);
    }

    private void stopDots() {
        uiHandler.removeCallbacks(dotsTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDots();
        if (currentCall != null) currentCall.cancel();
    }

    // ---- message actions (long-press a bubble) ----

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Gemini Legacy", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private View.OnLongClickListener longClickFor(final ChatMessage m) {
        return new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showMessageActions(m);
                return true;
            }
        };
    }

    private void showMessageActions(final ChatMessage m) {
        if (m.pending) return;
        final List<String> labels = new ArrayList<String>();
        if (m.text.length() > 0) {
            labels.add("Copy text");
            labels.add("Share");
        }
        final boolean canRegen = !sending && !m.fromUser && messages.indexOf(m) == messages.size() - 1;
        if (canRegen) labels.add(m.error ? "Retry" : "Regenerate reply");
        if (m.fromUser && !sending) labels.add("Edit & resend");
        if (labels.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String label = labels.get(which);
                        if (label.equals("Copy text")) copyText(m.text);
                        else if (label.equals("Share")) shareText(m.text);
                        else if (label.equals("Edit & resend")) editAndResend(m);
                        else regenerate();
                    }
                })
                .show();
    }

    /** Puts one of your messages back in the text box and drops it and everything after it. */
    private void editAndResend(ChatMessage m) {
        int idx = messages.indexOf(m);
        if (idx < 0 || sending) return;
        String text = m.text;
        byte[] image = m.image;
        messages.subList(idx, messages.size()).clear();
        adapter.notifyDataSetChanged();

        etMessage.setText(text);
        etMessage.setSelection(text.length());
        if (image != null) {
            pendingImage = image;
            ivAttach.setImageBitmap(ImageUtil.decodeForDisplay(image, 160));
            attachRow.setVisibility(View.VISIBLE);
        } else {
            clearAttachment();
        }
        etMessage.requestFocus();
    }

    // ---- saving generated images ----

    private void saveImage(ChatMessage m) {
        if (m.image != null) saveBytes(m.image, m.mimeType);
    }

    private void saveBytes(byte[] data, String mimeType) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "GeminiLegacy");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create " + dir);
            String ext = mimeType != null && mimeType.contains("jpeg") ? ".jpg" : ".png";
            File out = new File(dir, "gemini_" + System.currentTimeMillis() + ext);
            FileOutputStream fos = new FileOutputStream(out);
            try { fos.write(data); } finally { fos.close(); }
            MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()}, null, null);
            Toast.makeText(this, "Saved to Pictures/GeminiLegacy", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- adapters ----

    private class ChatListAdapter extends BaseAdapter {
        @Override public int getCount() { return chats.size(); }
        @Override public Object getItem(int position) { return chats.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
            ChatStore.Summary s = chats.get(position);
            int pad = (int) (14 * density);
            tv.setPadding(pad, pad, pad, pad);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTextSize(15);
            tv.setText(s.title);
            boolean current = s.id.equals(currentChatId);
            tv.setTextColor(current ? 0xFF8AB4F8 : 0xFFFFFFFF);
            tv.setBackgroundColor(current ? 0xFF2A2F3A : 0x00000000);
            return tv;
        }
    }

    /** Downloads up to 4 images the reply linked to and adds them under the text as they arrive. */
    private void loadLinkImages(final ChatMessage m) {
        final List<String> urls = Markdown.imageUrls(m.text, 4);
        for (final String url : urls) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final byte[] bytes = GeminiClient.downloadBytes(url, 8 * 1024 * 1024);
                        final Bitmap bmp = ImageUtil.decodeForDisplay(bytes, 800);
                        if (bmp == null) return;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                m.linkImages.add(new ChatMessage.LinkImage(bytes, bmp));
                                adapter.notifyDataSetChanged();
                            }
                        });
                    } catch (Exception ignored) { }
                }
            }).start();
        }
    }

    private TextView buildText(CharSequence text, boolean links, View.OnLongClickListener lc) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setLinkTextColor(0xFF8AB4F8);
        tv.setTextSize(15);
        tv.setMaxWidth(maxBubbleWidth);
        tv.setText(text);
        if (links) tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setOnLongClickListener(lc);
        return tv;
    }

    /** A fenced code block: dark card, sideways-scrolling monospace text, Copy button. */
    private View buildCode(final Markdown.CodeBlock block, View.OnLongClickListener lc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1B1B);
        bg.setCornerRadius(12 * density);
        card.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(maxBubbleWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (6 * density);
        lp.setMargins(0, margin, 0, margin);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (10 * density);
        head.setPadding(pad, (int) (2 * density), (int) (4 * density), 0);

        TextView lang = new TextView(this);
        lang.setText(block.lang.length() == 0 ? "code" : block.lang);
        lang.setTextColor(0xFF9AA0A6);
        lang.setTextSize(11);
        head.addView(lang, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView copy = new TextView(this);
        copy.setText("Copy");
        copy.setTextColor(0xFF8AB4F8);
        copy.setTextSize(12);
        copy.setTypeface(null, Typeface.BOLD);
        copy.setPadding(pad, (int) (6 * density), pad, (int) (6 * density));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyText(block.code);
                copy.setText("Copied");
            }
        });
        head.addView(copy);
        card.addView(head);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        TextView code = new TextView(this);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(12);
        code.setTextColor(0xFFE8EAED);
        code.setHorizontallyScrolling(true);
        code.setPadding(pad, 0, pad, pad);
        code.setText(block.code);
        scroll.addView(code);
        card.addView(scroll);

        card.setOnLongClickListener(lc);
        scroll.setOnLongClickListener(lc);
        return card;
    }

    /** A real grid: bordered cells, shaded bold header row, columns shrink to fit the screen. */
    private View buildTable(Markdown.Table table, View.OnLongClickListener lc) {
        int cols = 0;
        for (CharSequence[] row : table.rows) cols = Math.max(cols, row.length);

        android.widget.TableLayout layout = new android.widget.TableLayout(this);
        layout.setShrinkAllColumns(true);
        int margin = (int) (6 * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, margin, 0, margin);
        layout.setLayoutParams(lp);
        layout.setOnLongClickListener(lc);

        int pad = (int) (6 * density);
        for (int r = 0; r < table.rows.size(); r++) {
            CharSequence[] cells = table.rows.get(r);
            android.widget.TableRow tr = new android.widget.TableRow(this);
            for (int c = 0; c < cols; c++) {
                TextView cell = new TextView(this);
                cell.setText(c < cells.length ? cells[c] : "");
                cell.setTextColor(0xFFFFFFFF);
                cell.setTextSize(13);
                cell.setPadding(pad, pad, pad, pad);
                int a = table.align != null && c < table.align.length ? table.align[c] : 0;
                cell.setGravity(a == 1 ? Gravity.CENTER_HORIZONTAL : (a == 2 ? Gravity.RIGHT : Gravity.LEFT));
                if (r == 0) cell.setTypeface(null, Typeface.BOLD);
                GradientDrawable border = new GradientDrawable();
                border.setColor(r == 0 ? 0x33FFFFFF : 0x00000000);
                border.setStroke(1, 0x66FFFFFF);
                cell.setBackgroundDrawable(border);
                cell.setOnLongClickListener(lc);
                tr.addView(cell);
            }
            layout.addView(tr);
        }
        return layout;
    }

    private void showThoughts(TextView view, String thoughts, boolean visible) {
        if (!visible || thoughts.length() == 0) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(Markdown.render(thoughts));
        view.setVisibility(View.VISIBLE);
    }

    private class ChatAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Object getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_message, parent, false);
            final ChatMessage m = messages.get(position);
            View.OnLongClickListener lc = longClickFor(m);

            LinearLayout bubble = (LinearLayout) row.findViewById(R.id.bubble);
            LinearLayout body = (LinearLayout) row.findViewById(R.id.llBody);
            LinearLayout linkImages = (LinearLayout) row.findViewById(R.id.llLinkImages);
            ImageView iv = (ImageView) row.findViewById(R.id.ivImage);

            int color = m.error ? 0xFF8B2A2A : (m.fromUser ? 0xFF1A73E8 : 0xFF2D2D2D);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(24f);
            bubble.setBackgroundDrawable(bg);
            bubble.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    m.fromUser ? Gravity.RIGHT : Gravity.LEFT));
            bubble.setOnLongClickListener(lc);

            TextView header = (TextView) row.findViewById(R.id.tvThinkHeader);
            TextView thoughtsView = (TextView) row.findViewById(R.id.tvThoughts);
            header.setMaxWidth(maxBubbleWidth);
            thoughtsView.setMaxWidth(maxBubbleWidth);

            boolean waiting = m.pending && m.text.length() == 0 && m.image == null;
            if (waiting) {
                // Still thinking: animated dots, plus the live thought summary if Gemini sends one.
                StringBuilder dots = new StringBuilder("Thinking");
                for (int d = 0; d < dotPhase; d++) dots.append('.');
                header.setText(dots);
                header.setOnClickListener(null);
                header.setClickable(false);
                header.setVisibility(View.VISIBLE);
                showThoughts(thoughtsView, m.thoughts, m.thoughts.length() > 0);
            } else if (m.thoughts.length() > 0 && !m.fromUser && !m.error) {
                header.setText(m.thoughtsExpanded ? "Hide thinking" : "Show thinking");
                header.setVisibility(View.VISIBLE);
                header.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        m.thoughtsExpanded = !m.thoughtsExpanded;
                        adapter.notifyDataSetChanged();
                    }
                });
                showThoughts(thoughtsView, m.thoughts, m.thoughtsExpanded);
            } else {
                header.setVisibility(View.GONE);
                thoughtsView.setVisibility(View.GONE);
            }

            // Body: rebuilt only when the cached blocks change (text, tables, code), not on every dots tick.
            if (m.rendered == null) {
                if (m.fromUser || m.error) {
                    m.rendered = new ArrayList<Object>();
                    if (m.text.length() > 0) m.rendered.add(m.text);
                } else {
                    m.rendered = Markdown.parse(m.text);
                }
            }
            if (body.getTag() != m.rendered) {
                body.removeAllViews();
                boolean links = !(m.fromUser || m.error);
                for (Object block : m.rendered) {
                    View v;
                    if (block instanceof Markdown.Table) v = buildTable((Markdown.Table) block, lc);
                    else if (block instanceof Markdown.CodeBlock) v = buildCode((Markdown.CodeBlock) block, lc);
                    else v = buildText((CharSequence) block, links, lc);
                    body.addView(v);
                }
                body.setTag(m.rendered);
            }

            // Images that Gemini linked to in its text.
            if (!m.pending && !m.fromUser && !m.error && !m.linksRequested) {
                m.linksRequested = true;
                loadLinkImages(m);
            }
            int wantTag = m.linkImages.size() + 1000 * layoutGen;
            Object tag = linkImages.getTag();
            if (tag == null || (Integer) tag != wantTag || linkImages.getChildCount() != m.linkImages.size()) {
                linkImages.removeAllViews();
                for (final ChatMessage.LinkImage li : m.linkImages) {
                    ImageView v = new ImageView(MainActivity.this);
                    v.setAdjustViewBounds(true);
                    v.setMaxWidth(maxBubbleWidth);
                    v.setMaxHeight((int) (300 * density));
                    v.setImageBitmap(li.bitmap);
                    v.setPadding(0, (int) (8 * density), 0, 0);
                    v.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) { saveBytes(li.bytes, "image/jpeg"); }
                    });
                    linkImages.addView(v);
                }
                linkImages.setTag(wantTag);
            }

            // A visible Retry button under the last reply when it failed.
            TextView retry = (TextView) row.findViewById(R.id.tvRetry);
            if (m.error && !sending && messages.get(messages.size() - 1) == m) {
                retry.setVisibility(View.VISIBLE);
                retry.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { regenerate(); }
                });
            } else {
                retry.setVisibility(View.GONE);
            }

            if (m.thumb != null) {
                iv.setImageBitmap(m.thumb);
                iv.setVisibility(View.VISIBLE);
                iv.setOnLongClickListener(lc);
                if (m.fromUser) {
                    iv.setOnClickListener(null);
                    iv.setClickable(false);
                } else {
                    iv.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { saveImage(m); }
                    });
                }
            } else {
                iv.setImageBitmap(null);
                iv.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
