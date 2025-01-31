/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import org.session.libsession.messaging.messages.visible.LinkPreview;
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation;
import org.session.libsession.messaging.messages.visible.Quote;
import org.session.libsession.messaging.messages.visible.VisibleMessage;
import org.session.libsession.messaging.open_groups.OpenGroupV2;
import org.session.libsession.messaging.sending_receiving.MessageSender;
import org.session.libsession.messaging.utilities.UpdateMessageData;
import org.thoughtcrime.securesms.MessageDetailsRecipientAdapter.RecipientDeliveryStatus;
import org.session.libsession.utilities.MaterialColor;
import org.thoughtcrime.securesms.conversation.ConversationItem;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase.GroupReceiptInfo;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.loaders.MessageDetailsLoader;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.loki.database.LokiMessageDatabase;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.RecipientModifiedListener;
import org.thoughtcrime.securesms.util.DateUtils;
import org.session.libsession.utilities.ExpirationUtil;
import org.session.libsession.utilities.Util;
import org.session.libsignal.utilities.guava.Optional;

import java.lang.ref.WeakReference;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import network.loki.messenger.R;

/**
 * @author Jake McGinty
 */
public class MessageDetailsActivity extends PassphraseRequiredActionBarActivity implements LoaderCallbacks<Cursor>, RecipientModifiedListener {
  private final static String TAG = MessageDetailsActivity.class.getSimpleName();

  public final static String MESSAGE_ID_EXTRA     = "message_id";
  public final static String THREAD_ID_EXTRA      = "thread_id";
  public final static String IS_PUSH_GROUP_EXTRA  = "is_push_group";
  public final static String TYPE_EXTRA           = "type";
  public final static String ADDRESS_EXTRA        = "address";

  private GlideRequests    glideRequests;
  private long             threadId;
  private boolean          isPushGroup;
  private ConversationItem conversationItem;
  private ViewGroup        itemParent;
  private View             metadataContainer;
  private View             expiresContainer;
  private TextView         errorText;
  private View             resendButton;
  private TextView         sentDate;
  private TextView         receivedDate;
  private TextView         expiresInText;
  private View             receivedContainer;
  private TextView         transport;
  private TextView         toFrom;
  private View             separator;
  private ListView         recipientsList;
  private LayoutInflater   inflater;

  private boolean running;

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    setContentView(R.layout.message_details_activity);
    running = true;

    initializeResources();
    initializeActionBar();
    getSupportLoaderManager().initLoader(0, null, this);
  }

  @Override
  protected void onResume() {
    super.onResume();

    assert getSupportActionBar() != null;
    getSupportActionBar().setTitle("Message Details");

    ApplicationContext.getInstance(this).messageNotifier.setVisibleThread(threadId);
  }

  @Override
  protected void onPause() {
    super.onPause();
    ApplicationContext.getInstance(this).messageNotifier.setVisibleThread(-1L);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    running = false;
  }

  private void initializeActionBar() {
    assert getSupportActionBar() != null;

    Recipient recipient = Recipient.from(this, getIntent().getParcelableExtra(ADDRESS_EXTRA), true);
    recipient.addListener(this);
  }

  private void setActionBarColor(MaterialColor color) {
    assert getSupportActionBar() != null;
    getSupportActionBar().setBackgroundDrawable(new ColorDrawable(color.toActionBarColor(this)));
  }

  @Override
  public void onModified(final Recipient recipient) {
    Util.runOnMain(() -> setActionBarColor(recipient.getColor()));
  }

  private void initializeResources() {
    inflater       = LayoutInflater.from(this);
    View header = inflater.inflate(R.layout.message_details_header, recipientsList, false);

    threadId          = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);
    isPushGroup       = getIntent().getBooleanExtra(IS_PUSH_GROUP_EXTRA, false);
    glideRequests     = GlideApp.with(this);
    itemParent        = header.findViewById(R.id.item_container);
    recipientsList    = findViewById(R.id.recipients_list);
    metadataContainer = header.findViewById(R.id.metadata_container);
    errorText         = header.findViewById(R.id.error_text);
    resendButton      = header.findViewById(R.id.resend_button);
    sentDate          = header.findViewById(R.id.sent_time);
    receivedContainer = header.findViewById(R.id.received_container);
    receivedDate      = header.findViewById(R.id.received_time);
    transport         = header.findViewById(R.id.transport);
    toFrom            = header.findViewById(R.id.tofrom);
    separator         = header.findViewById(R.id.separator);
    expiresContainer  = header.findViewById(R.id.expires_container);
    expiresInText     = header.findViewById(R.id.expires_in);
    recipientsList.setHeaderDividersEnabled(false);
    recipientsList.addHeaderView(header, null, false);
  }

  private void updateTransport(MessageRecord messageRecord) {
    final String transportText;
    if (messageRecord.isOutgoing() && messageRecord.isFailed()) {
      transportText = "-";
    } else if (messageRecord.isPending()) {
      transportText = getString(R.string.ConversationFragment_pending);
    } else if (messageRecord.isPush()) {
      transportText = getString(R.string.ConversationFragment_push);
    } else if (messageRecord.isMms()) {
      transportText = getString(R.string.ConversationFragment_mms);
    } else {
      transportText = getString(R.string.ConversationFragment_sms);
    }

    transport.setText(transportText);
  }

  private void updateTime(MessageRecord messageRecord) {
    sentDate.setOnLongClickListener(null);
    receivedDate.setOnLongClickListener(null);

    if (messageRecord.isPending() || messageRecord.isFailed()) {
      sentDate.setText("-");
      receivedContainer.setVisibility(View.GONE);
    } else {
      Locale           dateLocale    = Locale.getDefault();
      SimpleDateFormat dateFormatter = DateUtils.getDetailedDateFormatter(this, dateLocale);
      sentDate.setText(dateFormatter.format(new Date(messageRecord.getDateSent())));
      sentDate.setOnLongClickListener(v -> {
        copyToClipboard(String.valueOf(messageRecord.getDateSent()));
        return true;
      });

      if (messageRecord.getDateReceived() != messageRecord.getDateSent() && !messageRecord.isOutgoing()) {
        receivedDate.setText(dateFormatter.format(new Date(messageRecord.getDateReceived())));
        receivedDate.setOnLongClickListener(v -> {
          copyToClipboard(String.valueOf(messageRecord.getDateReceived()));
          return true;
        });
        receivedContainer.setVisibility(View.VISIBLE);
      } else {
        receivedContainer.setVisibility(View.GONE);
      }
    }
  }

  private void updateExpirationTime(final MessageRecord messageRecord) {
    if (messageRecord.getExpiresIn() <= 0 || messageRecord.getExpireStarted() <= 0) {
      expiresContainer.setVisibility(View.GONE);
      return;
    }

    expiresContainer.setVisibility(View.VISIBLE);
    Util.runOnMain(new Runnable() {
      @Override
      public void run() {
        long elapsed   = System.currentTimeMillis() - messageRecord.getExpireStarted();
        long remaining = messageRecord.getExpiresIn() - elapsed;

        String duration = ExpirationUtil.getExpirationDisplayValue(MessageDetailsActivity.this, Math.max((int)(remaining / 1000), 1));
        expiresInText.setText(duration);

        if (running) {
          Util.runOnMainDelayed(this, 500);
        }
      }
    });
  }

  private void updateRecipients(MessageRecord messageRecord, Recipient recipient, List<RecipientDeliveryStatus> recipients) {
    final int toFromRes;
    if (messageRecord.isMms() && !messageRecord.isPush() && !messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__with;
    } else if (messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__to;
    } else {
      toFromRes = R.string.message_details_header__from;
    }
    toFrom.setText(toFromRes);
    long threadID = messageRecord.getThreadId();
    OpenGroupV2 openGroup = DatabaseFactory.getLokiThreadDatabase(this).getOpenGroupChat(threadID);
    if (openGroup != null && messageRecord.isOutgoing()) {
      toFrom.setVisibility(View.GONE);
      separator.setVisibility(View.GONE);
    }
    conversationItem.bind(messageRecord, Optional.absent(), Optional.absent(), glideRequests, Locale.getDefault(), new HashSet<>(), recipient, null, false);
    recipientsList.setAdapter(new MessageDetailsRecipientAdapter(this, glideRequests, messageRecord, recipients, isPushGroup));
  }

  private void inflateMessageViewIfAbsent(MessageRecord messageRecord) {
    if (conversationItem == null) {
      if (messageRecord.isGroupAction()) {
        conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_update, itemParent, false);
      } else if (messageRecord.isOutgoing()) {
        conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_sent, itemParent, false);
      } else {
        conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_received, itemParent, false);
      }
      itemParent.addView(conversationItem);
    }
  }

  private @Nullable MessageRecord getMessageRecord(Context context, Cursor cursor, String type) {
    switch (type) {
      case MmsSmsDatabase.SMS_TRANSPORT:
        SmsDatabase        smsDatabase = DatabaseFactory.getSmsDatabase(context);
        SmsDatabase.Reader reader      = smsDatabase.readerFor(cursor);
        return reader.getNext();
      case MmsSmsDatabase.MMS_TRANSPORT:
        MmsDatabase        mmsDatabase = DatabaseFactory.getMmsDatabase(context);
        MmsDatabase.Reader mmsReader   = mmsDatabase.readerFor(cursor);
        return mmsReader.getNext();
      default:
        throw new AssertionError("no valid message type specified");
    }
  }

  private void copyToClipboard(@NonNull String text) {
    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("text", text));
  }

  @Override
  public @NonNull Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return new MessageDetailsLoader(this, getIntent().getStringExtra(TYPE_EXTRA),
                                    getIntent().getLongExtra(MESSAGE_ID_EXTRA, -1));
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
    MessageRecord messageRecord = getMessageRecord(this, cursor, getIntent().getStringExtra(TYPE_EXTRA));

    if (messageRecord == null) {
      finish();
    } else {
      new MessageRecipientAsyncTask(this, messageRecord).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> loader) {
    recipientsList.setAdapter(null);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case android.R.id.home: finish(); return true;
    }

    return false;
  }

  @SuppressLint("StaticFieldLeak")
  private class MessageRecipientAsyncTask extends AsyncTask<Void,Void,List<RecipientDeliveryStatus>> {

    private final WeakReference<Context> weakContext;
    private final MessageRecord          messageRecord;

    MessageRecipientAsyncTask(@NonNull Context context, @NonNull MessageRecord messageRecord) {
      this.weakContext   = new WeakReference<>(context);
      this.messageRecord = messageRecord;
    }

    protected Context getContext() {
      return weakContext.get();
    }

    @Override
    public List<RecipientDeliveryStatus> doInBackground(Void... voids) {
      Context context = getContext();

      if (context == null) {
        Log.w(TAG, "associated context is destroyed, finishing early");
        return null;
      }

      List<RecipientDeliveryStatus> recipients = new LinkedList<>();

      if (!messageRecord.getRecipient().isGroupRecipient()) {
        recipients.add(new RecipientDeliveryStatus(messageRecord.getRecipient(), getStatusFor(messageRecord.getDeliveryReceiptCount(), messageRecord.getReadReceiptCount(), messageRecord.isPending()), messageRecord.isUnidentified(), -1));
      } else {
        List<GroupReceiptInfo> receiptInfoList = DatabaseFactory.getGroupReceiptDatabase(context).getGroupReceiptInfo(messageRecord.getId());

        if (receiptInfoList.isEmpty()) {
          List<Recipient> group = DatabaseFactory.getGroupDatabase(context).getGroupMembers(messageRecord.getRecipient().getAddress().toGroupString(), false);

          for (Recipient recipient : group) {
            recipients.add(new RecipientDeliveryStatus(recipient, RecipientDeliveryStatus.Status.UNKNOWN, false, -1));
          }
        } else {
          for (GroupReceiptInfo info : receiptInfoList) {
            recipients.add(new RecipientDeliveryStatus(Recipient.from(context, info.getAddress(), true),
                                                       getStatusFor(info.getStatus(), messageRecord.isPending(), messageRecord.isFailed()),
                                                       info.isUnidentified(),
                                                       info.getTimestamp()));
          }
        }
      }

      return recipients;
    }

    @Override
    public void onPostExecute(List<RecipientDeliveryStatus> recipients) {
      if (getContext() == null) {
        Log.w(TAG, "AsyncTask finished with a destroyed context, leaving early.");
        return;
      }

      inflateMessageViewIfAbsent(messageRecord);
      updateRecipients(messageRecord, messageRecord.getRecipient(), recipients);

      boolean isGroupNetworkFailure      = messageRecord.isFailed() && !messageRecord.getNetworkFailures().isEmpty();
      boolean isIndividualNetworkFailure = messageRecord.isFailed() && !isPushGroup && messageRecord.getIdentityKeyMismatches().isEmpty();

      LokiMessageDatabase lokiMessageDatabase = DatabaseFactory.getLokiMessageDatabase(getContext());
      String errorMessage = lokiMessageDatabase.getErrorMessage(messageRecord.id);
      if (errorMessage != null) {
        errorText.setText(errorMessage);
      }

      if (isGroupNetworkFailure || isIndividualNetworkFailure) {
        errorText.setVisibility(View.VISIBLE);
        resendButton.setVisibility(View.VISIBLE);
        resendButton.setOnClickListener(this::onResendClicked);
        metadataContainer.setVisibility(View.GONE);
      } else if (messageRecord.isFailed()) {
        errorText.setVisibility(View.VISIBLE);
        resendButton.setVisibility(View.GONE);
        resendButton.setOnClickListener(null);
        metadataContainer.setVisibility(View.GONE);
      } else {
        updateTransport(messageRecord);
        updateTime(messageRecord);
        updateExpirationTime(messageRecord);
        errorText.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        resendButton.setOnClickListener(null);
        metadataContainer.setVisibility(View.VISIBLE);
      }
    }

    private RecipientDeliveryStatus.Status getStatusFor(int deliveryReceiptCount, int readReceiptCount, boolean pending) {
      if      (readReceiptCount > 0)     return RecipientDeliveryStatus.Status.READ;
      else if (deliveryReceiptCount > 0) return RecipientDeliveryStatus.Status.DELIVERED;
      else if (!pending)                 return RecipientDeliveryStatus.Status.SENT;
      else                               return RecipientDeliveryStatus.Status.PENDING;
    }

    private RecipientDeliveryStatus.Status getStatusFor(int groupStatus, boolean pending, boolean failed) {
      if      (groupStatus == GroupReceiptDatabase.STATUS_READ)                    return RecipientDeliveryStatus.Status.READ;
      else if (groupStatus == GroupReceiptDatabase.STATUS_DELIVERED)               return RecipientDeliveryStatus.Status.DELIVERED;
      else if (groupStatus == GroupReceiptDatabase.STATUS_UNDELIVERED && failed)   return RecipientDeliveryStatus.Status.UNKNOWN;
      else if (groupStatus == GroupReceiptDatabase.STATUS_UNDELIVERED && !pending) return RecipientDeliveryStatus.Status.SENT;
      else if (groupStatus == GroupReceiptDatabase.STATUS_UNDELIVERED)             return RecipientDeliveryStatus.Status.PENDING;
      else if (groupStatus == GroupReceiptDatabase.STATUS_UNKNOWN)                 return RecipientDeliveryStatus.Status.UNKNOWN;
      throw new AssertionError();
    }

    private void onResendClicked(View v) {
      Recipient recipient = messageRecord.getRecipient();
      VisibleMessage message = new VisibleMessage();
      message.setId(messageRecord.getId());
      if (messageRecord.isOpenGroupInvitation()) {
        OpenGroupInvitation openGroupInvitation = new OpenGroupInvitation();
        UpdateMessageData updateMessageData = UpdateMessageData.Companion.fromJSON(messageRecord.getBody());
        if (updateMessageData.getKind() instanceof UpdateMessageData.Kind.OpenGroupInvitation) {
          UpdateMessageData.Kind.OpenGroupInvitation data = (UpdateMessageData.Kind.OpenGroupInvitation)updateMessageData.getKind();
          openGroupInvitation.setName(data.getGroupName());
          openGroupInvitation.setUrl(data.getGroupUrl());
        }
        message.setOpenGroupInvitation(openGroupInvitation);
      } else {
        message.setText(messageRecord.getBody());
      }
      message.setSentTimestamp(messageRecord.getTimestamp());
      if (recipient.isGroupRecipient()) {
        message.setGroupPublicKey(recipient.getAddress().toGroupString());
      } else {
        message.setRecipient(messageRecord.getRecipient().getAddress().serialize());
      }
      message.setThreadID(messageRecord.getThreadId());
      if (messageRecord.isMms()) {
        MmsMessageRecord mmsMessageRecord = (MmsMessageRecord) messageRecord;
        if (!mmsMessageRecord.getLinkPreviews().isEmpty()) {
          message.setLinkPreview(LinkPreview.Companion.from(mmsMessageRecord.getLinkPreviews().get(0)));
        }
        if (mmsMessageRecord.getQuote() != null) {
          message.setQuote(Quote.Companion.from(mmsMessageRecord.getQuote().getQuoteModel()));
        }
        message.addSignalAttachments(mmsMessageRecord.getSlideDeck().asAttachments());
      }
      MessageSender.send(message, recipient.getAddress());
      resendButton.setVisibility(View.GONE);
    }
  }
}
