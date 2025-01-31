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
package org.thoughtcrime.securesms.conversation;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import com.annimon.stream.Stream;
import org.session.libsession.messaging.MessagingModuleConfiguration;
import org.session.libsession.messaging.messages.control.DataExtractionNotification;
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage;
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage;
import org.session.libsession.messaging.messages.visible.Quote;
import org.session.libsession.messaging.messages.visible.VisibleMessage;
import org.session.libsession.messaging.open_groups.OpenGroupAPIV2;
import org.session.libsession.messaging.open_groups.OpenGroupV2;
import org.session.libsession.messaging.sending_receiving.MessageSender;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.ViewUtil;
import org.session.libsession.utilities.concurrent.SimpleTask;
import org.session.libsession.utilities.task.ProgressDialogAsyncTask;
import org.session.libsignal.utilities.guava.Optional;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.MessageDetailsActivity;
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.ShareActivity;
import org.thoughtcrime.securesms.components.ConversationTypingView;
import org.thoughtcrime.securesms.components.recyclerview.SmoothScrollingLinearLayoutManager;
import org.thoughtcrime.securesms.conversation.ConversationAdapter.HeaderViewHolder;
import org.thoughtcrime.securesms.conversation.ConversationAdapter.ItemClickListener;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.loaders.ConversationLoader;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.longmessage.LongMessageActivity;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kotlin.Unit;
import network.loki.messenger.R;

@SuppressLint("StaticFieldLeak")
public class ConversationFragment extends Fragment
  implements LoaderManager.LoaderCallbacks<Cursor>
{
  private static final String TAG       = ConversationFragment.class.getSimpleName();
  private static final String KEY_LIMIT = "limit";

  private static final int PARTIAL_CONVERSATION_LIMIT = 500;
  private static final int SCROLL_ANIMATION_THRESHOLD = 50;
  private static final int CODE_ADD_EDIT_CONTACT      = 77;

  private final ActionModeCallback actionModeCallback     = new ActionModeCallback();
  private final ItemClickListener  selectionClickListener = new ConversationFragmentItemClickListener();

  private ConversationFragmentListener listener;

  private Recipient                   recipient;
  private long                        threadId;
  private long                        lastSeen;
  private int                         startingPosition;
  private int                         previousOffset;
  private int                         activeOffset;
  private boolean                     firstLoad;
  private long                        loaderStartTime;
  private ActionMode                  actionMode;
  private Locale                      locale;
  private RecyclerView                list;
  private RecyclerView.ItemDecoration lastSeenDecoration;
  private ViewSwitcher                topLoadMoreView;
  private ViewSwitcher                bottomLoadMoreView;
  private ConversationTypingView      typingView;
  private View                        composeDivider;
  private View                        scrollToBottomButton;
  private TextView                    scrollDateHeader;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    this.locale = (Locale) getArguments().getSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
    list                 = ViewUtil.findById(view, android.R.id.list);
    composeDivider       = ViewUtil.findById(view, R.id.compose_divider);
    scrollToBottomButton = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
    scrollDateHeader     = ViewUtil.findById(view, R.id.scroll_date_header);

    scrollToBottomButton.setOnClickListener(v -> scrollToBottom());

    final LinearLayoutManager layoutManager = new SmoothScrollingLinearLayoutManager(getActivity(), true);
    list.setHasFixedSize(false);
    list.setLayoutManager(layoutManager);
    list.setItemAnimator(null);

    topLoadMoreView    = (ViewSwitcher) inflater.inflate(R.layout.load_more_header, container, false);
    bottomLoadMoreView = (ViewSwitcher) inflater.inflate(R.layout.load_more_header, container, false);
    initializeLoadMoreView(topLoadMoreView);
    initializeLoadMoreView(bottomLoadMoreView);

    typingView = (ConversationTypingView) inflater.inflate(R.layout.conversation_typing_view, container, false);

    return view;
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    initializeResources();
    initializeListAdapter();
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    this.listener = (ConversationFragmentListener)activity;
  }

  @Override
  public void onStart() {
    super.onStart();
    initializeTypingObserver();
  }

  @Override
  public void onResume() {
    super.onResume();

    if (list.getAdapter() != null) {
      list.getAdapter().notifyDataSetChanged();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    ApplicationContext.getInstance(requireContext()).getTypingStatusRepository().getTypists(threadId).removeObservers(this);
  }

  public void onNewIntent() {
    if (actionMode != null) {
      actionMode.finish();
    }

    initializeResources();
    initializeListAdapter();

    if (threadId == -1) {
      getLoaderManager().restartLoader(0, Bundle.EMPTY, this);
    }
  }

  public void reloadList() {
    getLoaderManager().restartLoader(0, Bundle.EMPTY, this);
  }

  public void moveToLastSeen() {
    if (lastSeen <= 0) {
      Log.i(TAG, "No need to move to last seen.");
      return;
    }

    if (list == null || getListAdapter() == null) {
      Log.w(TAG, "Tried to move to last seen position, but we hadn't initialized the view yet.");
      return;
    }

    int position = getListAdapter().findLastSeenPosition(lastSeen);
    scrollToLastSeenPosition(position);
  }

  private void initializeResources() {
    this.recipient         = Recipient.from(getActivity(), getActivity().getIntent().getParcelableExtra(ConversationActivity.ADDRESS_EXTRA), true);
    this.threadId          = this.getActivity().getIntent().getLongExtra(ConversationActivity.THREAD_ID_EXTRA, -1);
    this.lastSeen          = this.getActivity().getIntent().getLongExtra(ConversationActivity.LAST_SEEN_EXTRA, -1);
    this.startingPosition  = this.getActivity().getIntent().getIntExtra(ConversationActivity.STARTING_POSITION_EXTRA, -1);
    this.firstLoad         = true;

    OnScrollListener scrollListener = new ConversationScrollListener(getActivity());
    list.addOnScrollListener(scrollListener);
  }

  private void initializeListAdapter() {
    if (this.recipient != null && this.threadId != -1) {
      ConversationAdapter adapter = new ConversationAdapter(getActivity(), GlideApp.with(this), locale, selectionClickListener, null, this.recipient);
      list.setAdapter(adapter);
      list.addItemDecoration(new StickyHeaderDecoration(adapter, false, false));

      setLastSeen(lastSeen);
      getLoaderManager().restartLoader(0, Bundle.EMPTY, this);
    }
  }

  private void initializeLoadMoreView(ViewSwitcher loadMoreView) {
    loadMoreView.setOnClickListener(v -> {
      Bundle args = new Bundle();
      args.putInt(KEY_LIMIT, 0);
      getLoaderManager().restartLoader(0, args, ConversationFragment.this);
      loadMoreView.showNext();
      loadMoreView.setOnClickListener(null);
    });
  }

  private void initializeTypingObserver() {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(requireContext())) {
      return;
    }

    ApplicationContext.getInstance(requireContext()).getTypingStatusRepository().getTypists(threadId).observe(this, typingState ->  {
      List<Recipient> recipients;
      boolean         replacedByIncomingMessage;

      if (typingState != null) {
        recipients                = typingState.getTypists();
        replacedByIncomingMessage = typingState.isReplacedByIncomingMessage();
      } else {
        recipients                = Collections.emptyList();
        replacedByIncomingMessage = false;
      }

      typingView.setTypists(GlideApp.with(ConversationFragment.this), recipients, recipient.isGroupRecipient());

      ConversationAdapter adapter = getListAdapter();

      if (adapter.getHeaderView() != null && adapter.getHeaderView() != typingView) {
        Log.i(TAG, "Skipping typing indicator -- the header slot is occupied.");
        return;
      }

      if (recipients.size() > 0) {
        if (adapter.getHeaderView() == null && isAtBottom()) {
          list.setVerticalScrollBarEnabled(false);
          list.post(() -> getListLayoutManager().smoothScrollToPosition(requireContext(), 0, 250));
          list.postDelayed(() -> list.setVerticalScrollBarEnabled(true), 300);
          adapter.setHeaderView(typingView);
          adapter.notifyItemInserted(0);
        } else {
          if (adapter.getHeaderView() == null) {
            adapter.setHeaderView(typingView);
            adapter.notifyItemInserted(0);
          } else  {
            adapter.setHeaderView(typingView);
            adapter.notifyItemChanged(0);
          }
        }
      } else {
        if (getListLayoutManager().findFirstCompletelyVisibleItemPosition() == 0 && getListLayoutManager().getItemCount() > 1 && !replacedByIncomingMessage) {
          getListLayoutManager().smoothScrollToPosition(requireContext(), 1, 250);
          list.setVerticalScrollBarEnabled(false);
          list.postDelayed(() -> {
            adapter.setHeaderView(null);
            adapter.notifyItemRemoved(0);
            list.post(() -> list.setVerticalScrollBarEnabled(true));
          }, 200);
        } else if (!replacedByIncomingMessage) {
          adapter.setHeaderView(null);
          adapter.notifyItemRemoved(0);
        } else {
          adapter.setHeaderView(null);
        }
      }
    });
  }

  private void setCorrectMenuVisibility(Menu menu) {
    Set<MessageRecord> messageRecords = getListAdapter().getSelectedItems();
    boolean            actionMessage  = false;
    boolean            hasText        = false;
    boolean            sharedContact  = false;

    if (actionMode != null && messageRecords.size() == 0) {
      actionMode.finish();
      return;
    }

    for (MessageRecord messageRecord : messageRecords) {
      if (messageRecord.isGroupAction() || messageRecord.isCallLog() ||
          messageRecord.isJoined() || messageRecord.isExpirationTimerUpdate() ||
          messageRecord.isEndSession() || messageRecord.isIdentityUpdate() ||
          messageRecord.isIdentityVerified() || messageRecord.isIdentityDefault() || messageRecord.isLokiSessionRestoreSent() || messageRecord.isLokiSessionRestoreDone())
      {
        actionMessage = true;
      }

      if (messageRecord.getBody().length() > 0) {
        hasText = true;
      }

      if (messageRecord.isMms() && !((MmsMessageRecord) messageRecord).getSharedContacts().isEmpty()) {
        sharedContact = true;
      }
    }

    if (messageRecords.size() > 1) {
      menu.findItem(R.id.menu_context_details).setVisible(false);
      menu.findItem(R.id.menu_context_reply).setVisible(false);
      menu.findItem(R.id.menu_context_save_attachment).setVisible(false);
      menu.findItem(R.id.menu_context_resend).setVisible(false);
    } else {
      MessageRecord messageRecord = messageRecords.iterator().next();

      menu.findItem(R.id.menu_context_details).setVisible(true);
      menu.findItem(R.id.menu_context_resend).setVisible(messageRecord.isFailed());
      menu.findItem(R.id.menu_context_save_attachment).setVisible(!actionMessage                                              &&
                                                                  messageRecord.isMms()                                       &&
                                                                  !messageRecord.isMmsNotification()                          &&
                                                                  ((MediaMmsMessageRecord)messageRecord).containsMediaSlide());

      menu.findItem(R.id.menu_context_reply).setVisible(!actionMessage             &&
                                                        !messageRecord.isPending() &&
                                                        !messageRecord.isFailed()  &&
                                                        messageRecord.isSecure());
    }

    menu.findItem(R.id.menu_context_copy).setVisible(!actionMessage && hasText);

    boolean isGroupChat = recipient.isGroupRecipient();

    if (isGroupChat) {
      OpenGroupV2 openGroupChat = DatabaseFactory.getLokiThreadDatabase(getContext()).getOpenGroupChat(threadId);
      boolean isPublicChat = (openGroupChat != null);
      int selectedMessageCount = messageRecords.size();
      boolean areAllSentByUser = true;
      Set<String> uniqueUserSet = new HashSet<>();
      for (MessageRecord message : messageRecords) {
        if (!message.isOutgoing()) { areAllSentByUser = false; }
        uniqueUserSet.add(message.getRecipient().getAddress().toString());
      }
      menu.findItem(R.id.menu_context_copy_public_key).setVisible(selectedMessageCount == 1 && !areAllSentByUser);
      menu.findItem(R.id.menu_context_reply).setVisible(selectedMessageCount == 1);
      String userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(requireContext());
      boolean userCanModerate =
        (isPublicChat && (OpenGroupAPIV2.isUserModerator(userHexEncodedPublicKey, openGroupChat.getRoom(), openGroupChat.getServer())));
      boolean isDeleteOptionVisible = !isPublicChat || (areAllSentByUser || userCanModerate);
      // allow banning if moderating a public chat and only one user's messages are selected
      boolean isBanOptionVisible = isPublicChat && userCanModerate && !areAllSentByUser && uniqueUserSet.size() == 1;
      menu.findItem(R.id.menu_context_delete_message).setVisible(isDeleteOptionVisible);
      menu.findItem(R.id.menu_context_ban_user).setVisible(isBanOptionVisible);
    } else {
      menu.findItem(R.id.menu_context_copy_public_key).setVisible(false);
      menu.findItem(R.id.menu_context_delete_message).setVisible(true);
      menu.findItem(R.id.menu_context_ban_user).setVisible(false);
    }
  }

  private ConversationAdapter getListAdapter() {
    return (ConversationAdapter) list.getAdapter();
  }

  private SmoothScrollingLinearLayoutManager getListLayoutManager() {
    return (SmoothScrollingLinearLayoutManager) list.getLayoutManager();
  }

  private MessageRecord getSelectedMessageRecord() {
    Set<MessageRecord> messageRecords = getListAdapter().getSelectedItems();
    return messageRecords.iterator().next();
  }

  public void reload(Recipient recipient, long threadId) {
    this.recipient = recipient;

    if (this.threadId != threadId) {
      this.threadId = threadId;
      initializeListAdapter();
    }
  }

  public void scrollToBottom() {
    if (getListLayoutManager().findFirstVisibleItemPosition() < SCROLL_ANIMATION_THRESHOLD) {
      list.smoothScrollToPosition(0);
    } else {
      list.scrollToPosition(0);
    }
  }

  public void setLastSeen(long lastSeen) {
    this.lastSeen = lastSeen;
    if (lastSeenDecoration != null) {
      list.removeItemDecoration(lastSeenDecoration);
    }

    lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter(), lastSeen);
    list.addItemDecoration(lastSeenDecoration);
  }

  private void handleCopyMessage(final Set<MessageRecord> messageRecords) {
    List<MessageRecord> messageList = new LinkedList<>(messageRecords);
    Collections.sort(messageList, new Comparator<MessageRecord>() {
      @Override
      public int compare(MessageRecord lhs, MessageRecord rhs) {
        if      (lhs.getDateReceived() < rhs.getDateReceived())  return -1;
        else if (lhs.getDateReceived() == rhs.getDateReceived()) return 0;
        else                                                     return 1;
      }
    });

    StringBuilder    bodyBuilder = new StringBuilder();
    ClipboardManager clipboard   = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);

    for (MessageRecord messageRecord : messageList) {
      String body = messageRecord.getDisplayBody(requireContext()).toString();
      if (!TextUtils.isEmpty(body)) {
        bodyBuilder.append(body).append('\n');
      }
    }
    if (bodyBuilder.length() > 0 && bodyBuilder.charAt(bodyBuilder.length() - 1) == '\n') {
      bodyBuilder.deleteCharAt(bodyBuilder.length() - 1);
    }

    String result = bodyBuilder.toString();

    if (!TextUtils.isEmpty(result))
        clipboard.setText(result);
  }

  private void handleCopyPublicKey(MessageRecord messageRecord) {
    String sessionID = messageRecord.getRecipient().getAddress().toString();
    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText("Session ID", sessionID);
    clipboard.setPrimaryClip(clip);
    Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
  }

  private void handleDeleteMessages(final Set<MessageRecord> messageRecords) {
    int                 messagesCount = messageRecords.size();
    AlertDialog.Builder builder       = new AlertDialog.Builder(getActivity());

    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(getActivity().getResources().getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messagesCount, messagesCount));
    builder.setMessage(getActivity().getResources().getQuantityString(R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages, messagesCount, messagesCount));
    builder.setCancelable(true);

    OpenGroupV2 openGroupChat = DatabaseFactory.getLokiThreadDatabase(getContext()).getOpenGroupChat(threadId);

    builder.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        new ProgressDialogAsyncTask<MessageRecord, Void, Void>(getActivity(),
                                                               R.string.ConversationFragment_deleting,
                                                               R.string.ConversationFragment_deleting_messages)
        {
          @Override
          protected Void doInBackground(MessageRecord... messageRecords) {
            if (openGroupChat != null) {
              ArrayList<Long> serverIDs = new ArrayList<>();
              ArrayList<Long> ignoredMessages = new ArrayList<>();
              ArrayList<Long> failedMessages = new ArrayList<>();
              boolean isSentByUser = true;
              for (MessageRecord messageRecord : messageRecords) {
                isSentByUser = isSentByUser && messageRecord.isOutgoing();
                Long serverID = DatabaseFactory.getLokiMessageDatabase(getContext()).getServerID(messageRecord.id, !messageRecord.isMms());
                if (serverID != null) {
                  serverIDs.add(serverID);
                } else {
                  ignoredMessages.add(messageRecord.getId());
                }
              }
              if (openGroupChat != null) {
                for (Long serverId : serverIDs) {
                  OpenGroupAPIV2
                          .deleteMessage(serverId, openGroupChat.getRoom(), openGroupChat.getServer())
                          .success(l -> {
                            for (MessageRecord messageRecord : messageRecords) {
                              Long serverID = DatabaseFactory.getLokiMessageDatabase(getContext()).getServerID(messageRecord.id,  !messageRecord.isMms());
                              if (serverID != null && serverID.equals(serverId)) {
                                MessagingModuleConfiguration.shared.getMessageDataProvider().deleteMessage(messageRecord.id, !messageRecord.isMms());
                                break;
                              }
                            }
                            return null;
                          }).fail(e->{
                            Log.e("Loki", "Couldn't delete message due to error",e);
                            return null;
                          });
                  }
                }
            } else {
              for (MessageRecord messageRecord : messageRecords) {
                if (messageRecord.isMms()) {
                  DatabaseFactory.getMmsDatabase(getActivity()).delete(messageRecord.getId());
                } else {
                  DatabaseFactory.getSmsDatabase(getActivity()).deleteMessage(messageRecord.getId());
                }
              }
            }
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, messageRecords.toArray(new MessageRecord[messageRecords.size()]));
      }
    });

    builder.setNegativeButton(android.R.string.cancel, null);
    builder.show();
  }

  private void handleBanUser(Set<MessageRecord> messageRecords) {
    AlertDialog.Builder builder       = new AlertDialog.Builder(getActivity());

    String userPublicKey = null;
    for (MessageRecord record: messageRecords) {
      String currentPublicKey = record.getRecipient().getAddress().toString();
      if (userPublicKey == null) {
        userPublicKey = currentPublicKey;
      }
    }
    final String finalPublicKey = userPublicKey;

    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(R.string.ConversationFragment_ban_selected_user);
    builder.setCancelable(true);

    final OpenGroupV2 openGroupChat = DatabaseFactory.getLokiThreadDatabase(getContext()).getOpenGroupChat(threadId);

    builder.setPositiveButton(R.string.ban, (dialog, which) -> {
      ConversationAdapter chatAdapter = getListAdapter();
      chatAdapter.clearSelection();
      chatAdapter.notifyDataSetChanged();
      new ProgressDialogAsyncTask<String, Void, Void>(getActivity(),
              R.string.ConversationFragment_banning,
              R.string.ConversationFragment_banning_user) {
        @Override
        protected Void doInBackground(String... userPublicKeyParam) {
          String userPublicKey = userPublicKeyParam[0];
          if (openGroupChat != null) {
            OpenGroupAPIV2
                    .ban(userPublicKey, openGroupChat.getRoom(), openGroupChat.getServer())
                    .success(l -> {
                      Log.d("Loki", "User banned");
                      return Unit.INSTANCE;
                    }).fail(e -> {
                      Log.e("Loki", "Failed to ban user",e);
                      return null;
                    });
          } else {
            Log.d("Loki", "Tried to ban user from a non-public chat");
          }
          return null;
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, finalPublicKey);
    });

    builder.setNegativeButton(android.R.string.cancel, null);
    builder.show();
  }

  private void handleDisplayDetails(MessageRecord message) {
    Intent intent = new Intent(getActivity(), MessageDetailsActivity.class);
    intent.putExtra(MessageDetailsActivity.MESSAGE_ID_EXTRA, message.getId());
    intent.putExtra(MessageDetailsActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(MessageDetailsActivity.TYPE_EXTRA, message.isMms() ? MmsSmsDatabase.MMS_TRANSPORT : MmsSmsDatabase.SMS_TRANSPORT);
    intent.putExtra(MessageDetailsActivity.ADDRESS_EXTRA, recipient.getAddress());
    intent.putExtra(MessageDetailsActivity.IS_PUSH_GROUP_EXTRA, recipient.isGroupRecipient() && message.isPush());
    startActivity(intent);
  }

  private void handleForwardMessage(MessageRecord message) {
    listener.onForwardClicked();

    SimpleTask.run(getLifecycle(), () -> {
      Intent composeIntent = new Intent(getActivity(), ShareActivity.class);
      composeIntent.putExtra(Intent.EXTRA_TEXT, message.getDisplayBody(requireContext()).toString());

      if (message.isMms()) {
        MmsMessageRecord mediaMessage = (MmsMessageRecord) message;
        boolean          isAlbum      = mediaMessage.containsMediaSlide()                      &&
                                        mediaMessage.getSlideDeck().getSlides().size() > 1     &&
                                        mediaMessage.getSlideDeck().getAudioSlide() == null    &&
                                        mediaMessage.getSlideDeck().getDocumentSlide() == null;

        if (isAlbum) {
          ArrayList<Media> mediaList   = new ArrayList<>(mediaMessage.getSlideDeck().getSlides().size());
          List<Attachment> attachments = Stream.of(mediaMessage.getSlideDeck().getSlides())
                                               .filter(s -> s.hasImage() || s.hasVideo())
                                               .map(Slide::asAttachment)
                                               .toList();

          for (Attachment attachment : attachments) {
            Uri uri = attachment.getDataUri() != null ? attachment.getDataUri() : attachment.getThumbnailUri();

            if (uri != null) {
              mediaList.add(new Media(uri,
                                      attachment.getContentType(),
                                      System.currentTimeMillis(),
                                      attachment.getWidth(),
                                      attachment.getHeight(),
                                      attachment.getSize(),
                                      Optional.absent(),
                                      Optional.fromNullable(attachment.getCaption())));
            }
          };

          if (!mediaList.isEmpty()) {
            composeIntent.putExtra(ConversationActivity.MEDIA_EXTRA, mediaList);
          }
        } else if (mediaMessage.containsMediaSlide()) {
          Slide slide = mediaMessage.getSlideDeck().getSlides().get(0);
          composeIntent.putExtra(Intent.EXTRA_STREAM, slide.getUri());
          composeIntent.setType(slide.getContentType());
        }

        if (mediaMessage.getSlideDeck().getTextSlide() != null && mediaMessage.getSlideDeck().getTextSlide().getUri() != null) {
          try (InputStream stream = PartAuthority.getAttachmentStream(requireContext(), mediaMessage.getSlideDeck().getTextSlide().getUri())) {
            String fullBody = Util.readFullyAsString(stream);
            composeIntent.putExtra(Intent.EXTRA_TEXT, fullBody);
          } catch (IOException e) {
            Log.w(TAG, "Failed to read long message text when forwarding.");
          }
        }
      }

      return composeIntent;
    }, this::startActivity);
  }

  private void handleResendMessage(final MessageRecord message) {
    new AsyncTask<MessageRecord, Void, Void>() {
      @Override
      protected Void doInBackground(MessageRecord... messageRecords) {
        MessageRecord messageRecord = messageRecords[0];
        Recipient recipient = messageRecord.getRecipient();
        VisibleMessage message = new VisibleMessage();
        message.setId(messageRecord.getId());
        message.setText(messageRecord.getBody());
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
            message.setLinkPreview(org.session.libsession.messaging.messages.visible.LinkPreview.Companion.from(mmsMessageRecord.getLinkPreviews().get(0)));
          }
          if (mmsMessageRecord.getQuote() != null) {
            message.setQuote(Quote.Companion.from(mmsMessageRecord.getQuote().getQuoteModel()));
          }
          message.addSignalAttachments(mmsMessageRecord.getSlideDeck().asAttachments());
        }
        MessageSender.send(message, recipient.getAddress());
        return null;
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, message);
  }

  private void handleReplyMessage(final MessageRecord message) {
    listener.handleReplyMessage(message);
  }

  private void handleSaveAttachment(final MediaMmsMessageRecord message) {
    SaveAttachmentTask.showWarningDialog(getActivity(), (dialog, which) -> {
      Permissions.with(this)
              .request(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              .maxSdkVersion(Build.VERSION_CODES.P)
              .withPermanentDenialDialog(getString(R.string.MediaPreviewActivity_signal_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
              .onAnyDenied(() -> Toast.makeText(getContext(), R.string.MediaPreviewActivity_unable_to_write_to_external_storage_without_permission, Toast.LENGTH_LONG).show())
              .onAllGranted(() -> {
                List<SaveAttachmentTask.Attachment> attachments =
                        Stream.of(message.getSlideDeck().getSlides())
                                .filter(s -> s.getUri() != null && (s.hasImage() || s.hasVideo() || s.hasAudio() || s.hasDocument()))
                                .map(s -> new SaveAttachmentTask.Attachment(s.getUri(), s.getContentType(), message.getDateReceived(), s.getFileName().orNull()))
                                .toList();
                if (!Util.isEmpty(attachments)) {
                  SaveAttachmentTask saveTask = new SaveAttachmentTask(getActivity());
                  saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, attachments.toArray(new SaveAttachmentTask.Attachment[0]));
                  // Sending a Data extraction notification (for incoming attachments only)
                  if(!message.isOutgoing()) {
                    //TODO uncomment line below when Data extraction will be activated
                    //sendMediaSavedNotificationIfNeeded();
                  }
                  return;
                }

                Log.w(TAG, "No slide with attachable media found, failing nicely.");
                Toast.makeText(getActivity(),
                        getResources().getQuantityString(R.plurals.ConversationFragment_error_while_saving_attachments_to_sd_card, 1),
                        Toast.LENGTH_LONG).show();
              })
              .execute();
    });
  }

  /**
   * Send a MediaSaved notification to the recipient
   */
  private void sendMediaSavedNotificationIfNeeded() {
    // we don't send media saved notification for groups
    if (recipient.isGroupRecipient()) return;
    DataExtractionNotification message = new DataExtractionNotification(new DataExtractionNotification.Kind.MediaSaved(System.currentTimeMillis()));
    MessageSender.send(message, recipient.getAddress());
  }

  @Override
  public @NonNull Loader<Cursor> onCreateLoader(int id, Bundle args) {
    Log.i(TAG, "onCreateLoader");
    loaderStartTime = System.currentTimeMillis();

    int limit  = args.getInt(KEY_LIMIT, PARTIAL_CONVERSATION_LIMIT);
    int offset = 0;
    if (limit != 0 && startingPosition >= limit) {
      offset = Math.max(startingPosition - (limit / 2) + 1, 0);
      startingPosition -= offset - 1;
    }

    return new ConversationLoader(getActivity(), threadId, offset, limit, lastSeen);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> cursorLoader, Cursor cursor) {
    long loadTime = System.currentTimeMillis() - loaderStartTime;
    int  count    = cursor.getCount();
    Log.i(TAG, "onLoadFinished - took " + loadTime + " ms to load a cursor of size " + count);
    ConversationLoader loader = (ConversationLoader)cursorLoader;

    ConversationAdapter adapter = getListAdapter();
    if (adapter == null) {
      return;
    }

    if (cursor.getCount() >= PARTIAL_CONVERSATION_LIMIT && loader.hasLimit()) {
      adapter.setFooterView(topLoadMoreView);
    } else {
      adapter.setFooterView(null);
    }

    if (lastSeen == -1) {
      setLastSeen(loader.getLastSeen());
    }

    if (!loader.hasSent() && !recipient.isSystemContact() && !recipient.isGroupRecipient() && recipient.getRegistered() == Recipient.RegisteredState.REGISTERED) {
//      adapter.setHeaderView(unknownSenderView);
    } else {
      clearHeaderIfNotTyping(adapter);
    }

    if (loader.hasOffset()) {
      adapter.setHeaderView(bottomLoadMoreView);
    }

    if (firstLoad || loader.hasOffset()) {
      previousOffset = loader.getOffset();
    }

    activeOffset = loader.getOffset();

    adapter.changeCursor(cursor);

    int lastSeenPosition = adapter.findLastSeenPosition(lastSeen);

    if (adapter.getHeaderView() == typingView) {
      lastSeenPosition = Math.max(lastSeenPosition - 1, 0);
    }

    if (firstLoad) {
      if (startingPosition >= 0) {
        scrollToStartingPosition(startingPosition);
      } else {
        scrollToLastSeenPosition(lastSeenPosition);
      }
      firstLoad = false;
    } else if (previousOffset > 0) {
      int scrollPosition = previousOffset + getListLayoutManager().findFirstVisibleItemPosition();
      scrollPosition = Math.min(scrollPosition, count - 1);

      View firstView = list.getLayoutManager().getChildAt(scrollPosition);
      int pixelOffset = (firstView == null) ? 0 : (firstView.getBottom() - list.getPaddingBottom());

      getListLayoutManager().scrollToPositionWithOffset(scrollPosition, pixelOffset);
      previousOffset = 0;
    }

    if (lastSeenPosition <= 0) {
      setLastSeen(0);
    }
  }

  private void clearHeaderIfNotTyping(ConversationAdapter adapter) {
    if (adapter.getHeaderView() != typingView) {
      adapter.setHeaderView(null);
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> arg0) {
    if (list.getAdapter() != null) {
      getListAdapter().changeCursor(null);
    }
  }

  public long stageOutgoingMessage(OutgoingMediaMessage message) {
    MessageRecord messageRecord = DatabaseFactory.getMmsDatabase(getContext()).readerFor(message, threadId).getCurrent();

    if (getListAdapter() != null) {
      clearHeaderIfNotTyping(getListAdapter());
      setLastSeen(0);
      getListAdapter().addFastRecord(messageRecord);
    }

    return messageRecord.getId();
  }

  public long stageOutgoingMessage(OutgoingTextMessage message) {
    MessageRecord messageRecord = DatabaseFactory.getSmsDatabase(getContext()).readerFor(message, threadId).getCurrent();

    if (getListAdapter() != null) {
      clearHeaderIfNotTyping(getListAdapter());
      setLastSeen(0);
      getListAdapter().addFastRecord(messageRecord);
    }

    return messageRecord.getId();
  }

  public void releaseOutgoingMessage(long id) {
    if (getListAdapter() != null) {
      getListAdapter().releaseFastRecord(id);
    }
  }

  private void scrollToStartingPosition(final int startingPosition) {
    list.post(() -> {
      list.getLayoutManager().scrollToPosition(startingPosition);
      getListAdapter().pulseHighlightItem(startingPosition);
    });
  }

  private void scrollToLastSeenPosition(final int lastSeenPosition) {
    if (lastSeenPosition > 0) {
      list.post(() -> getListLayoutManager().scrollToPositionWithOffset(lastSeenPosition, list.getHeight()));
    }
  }

  private boolean isAtBottom() {
    if (list.getChildCount() == 0) return true;

    int firstVisiblePosition = getListLayoutManager().findFirstVisibleItemPosition();

    if (getListAdapter().getHeaderView() == typingView) {
      RecyclerView.ViewHolder item1 = list.findViewHolderForAdapterPosition(1);
      return firstVisiblePosition <= 1 && item1 != null && item1.itemView.getBottom() <= list.getHeight();
    }

    return firstVisiblePosition == 0 && list.getChildAt(0).getBottom() <= list.getHeight();
  }

  public void onSearchQueryUpdated(@Nullable String query) {
    if (getListAdapter() != null) {
      getListAdapter().onSearchQueryUpdated(query);
    }
  }

  public void jumpToMessage(@NonNull Address author, long timestamp, @Nullable Runnable onMessageNotFound) {
    SimpleTask.run(getLifecycle(), () -> {
      return DatabaseFactory.getMmsSmsDatabase(getContext())
                            .getMessagePositionInConversation(threadId, timestamp, author);
    }, p -> moveToMessagePosition(p, onMessageNotFound));
  }

  private void moveToMessagePosition(int position, @Nullable Runnable onMessageNotFound) {
    Log.d(TAG, "Moving to message position: " + position + "  activeOffset: " + activeOffset + "  cursorCount: " + getListAdapter().getCursorCount());

    if (position >= activeOffset && position >= 0 && position < getListAdapter().getCursorCount()) {
      int offset = activeOffset > 0 ? activeOffset - 1 : 0;
      list.scrollToPosition(position - offset);
      getListAdapter().pulseHighlightItem(position - offset);
    } else if (position < 0) {
      Log.w(TAG, "Tried to navigate to message, but it wasn't found.");
      if (onMessageNotFound != null) {
        onMessageNotFound.run();
      }
    } else {
      Log.i(TAG, "Message was outside of the loaded range. Need to restart the loader.");

      firstLoad        = true;
      startingPosition = position;
      getLoaderManager().restartLoader(0, Bundle.EMPTY, ConversationFragment.this);
    }
  }

  public interface ConversationFragmentListener {
    void setThreadId(long threadId);
    void handleReplyMessage(MessageRecord messageRecord);
    void onMessageActionToolbarOpened();
    void onForwardClicked();
  }

  private class ConversationScrollListener extends OnScrollListener {

    private final Animation              scrollButtonInAnimation;
    private final Animation              scrollButtonOutAnimation;
    private final ConversationDateHeader conversationDateHeader;

    private boolean wasAtBottom           = true;
    private boolean wasAtZoomScrollHeight = false;
    private long    lastPositionId        = -1;

    ConversationScrollListener(@NonNull Context context) {
      this.scrollButtonInAnimation  = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
      this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);
      this.conversationDateHeader   = new ConversationDateHeader(context, scrollDateHeader);

      this.scrollButtonInAnimation.setDuration(100);
      this.scrollButtonOutAnimation.setDuration(50);
    }

    @Override
    public void onScrolled(@NonNull final RecyclerView rv, final int dx, final int dy) {
      boolean currentlyAtBottom           = isAtBottom();
      boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
      int     positionId                  = getHeaderPositionId();

      if (currentlyAtBottom && !wasAtBottom) {
        ViewUtil.fadeOut(composeDivider, 50, View.INVISIBLE);
        ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
      } else if (!currentlyAtBottom && wasAtBottom) {
        ViewUtil.fadeIn(composeDivider, 500);
      }

      if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
        ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
      }

      if (positionId != lastPositionId) {
        bindScrollHeader(conversationDateHeader, positionId);
      }

      wasAtBottom           = currentlyAtBottom;
      wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
      lastPositionId        = positionId;
    }

    @Override
    public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
      if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
        conversationDateHeader.show();
      } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
        conversationDateHeader.hide();
      }
    }

    private boolean isAtZoomScrollHeight() {
      return getListLayoutManager().findFirstCompletelyVisibleItemPosition() > 4;
    }

    private int getHeaderPositionId() {
      return getListLayoutManager().findLastVisibleItemPosition();
    }

    private void bindScrollHeader(HeaderViewHolder headerViewHolder, int positionId) {
      if (((ConversationAdapter)list.getAdapter()).getHeaderId(positionId) != -1) {
        ((ConversationAdapter) list.getAdapter()).onBindHeaderViewHolder(headerViewHolder, positionId);
      }
    }
  }

  private class ConversationFragmentItemClickListener implements ItemClickListener {

    @Override
    public void onItemClick(MessageRecord messageRecord) {
      if (actionMode != null) {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();

        if (getListAdapter().getSelectedItems().size() == 0) {
          actionMode.finish();
        } else {
          setCorrectMenuVisibility(actionMode.getMenu());
          actionMode.setTitle(String.valueOf(getListAdapter().getSelectedItems().size()));
        }
      }
    }

    @Override
    public void onItemLongClick(MessageRecord messageRecord) {
      if (actionMode == null) {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();

        actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(actionModeCallback);

        View titleTextView = (getActivity().findViewById(R.id.action_bar_title));
        if (titleTextView != null) {
          titleTextView.setBackgroundColor(getResources().getColor(R.color.transparent));
          ViewParent titleTextViewContainerView = titleTextView.getParent();
          if (titleTextViewContainerView != null) {
            ((View)titleTextViewContainerView).setBackgroundColor(getResources().getColor(R.color.transparent));
          }
        }
      }
    }

    @Override
    public void onQuoteClicked(MmsMessageRecord messageRecord) {
      if (messageRecord.getQuote() == null) {
        Log.w(TAG, "Received a 'quote clicked' event, but there's no quote...");
        return;
      }

      if (messageRecord.getQuote().isOriginalMissing()) {
        Log.i(TAG, "Clicked on a quote whose original message we never had.");
        Toast.makeText(getContext(), R.string.ConversationFragment_quoted_message_not_found, Toast.LENGTH_SHORT).show();
        return;
      }

      SimpleTask.run(getLifecycle(), () -> {
        return DatabaseFactory.getMmsSmsDatabase(getContext())
                              .getQuotedMessagePosition(threadId,
                                                        messageRecord.getQuote().getId(),
                                                        messageRecord.getQuote().getAuthor());
      }, p -> moveToMessagePosition(p, () -> {
        Toast.makeText(getContext(), R.string.ConversationFragment_quoted_message_no_longer_available, Toast.LENGTH_SHORT).show();
      }));
    }

    @Override
    public void onLinkPreviewClicked(@NonNull LinkPreview linkPreview) {
      if (getContext() != null && getActivity() != null) {
        CommunicationActions.openBrowserLink(getActivity(), linkPreview.getUrl());
      }
    }

    @Override
    public void onMoreTextClicked(@NonNull Address conversationAddress, long messageId, boolean isMms) {
      if (getContext() != null && getActivity() != null) {
        startActivity(LongMessageActivity.getIntent(getContext(), conversationAddress, messageId, isMms));
      }
    }
  }

  private class ActionModeCallback implements ActionMode.Callback {

    private int statusBarColor;

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.conversation_context, menu);

      mode.setTitle("1");

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Window window = getActivity().getWindow();
        statusBarColor = window.getStatusBarColor();
      }

      setCorrectMenuVisibility(menu);
      listener.onMessageActionToolbarOpened();
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      ((ConversationAdapter)list.getAdapter()).clearSelection();
      list.getAdapter().notifyDataSetChanged();

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        getActivity().getWindow().setStatusBarColor(statusBarColor);
      }

      actionMode = null;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      switch(item.getItemId()) {
        case R.id.menu_context_copy:
          handleCopyMessage(getListAdapter().getSelectedItems());
          actionMode.finish();
          return true;
        case R.id.menu_context_copy_public_key:
          handleCopyPublicKey((MessageRecord) getListAdapter().getSelectedItems().toArray()[0]);
          actionMode.finish();
          return true;
        case R.id.menu_context_delete_message:
          handleDeleteMessages(getListAdapter().getSelectedItems());
          actionMode.finish();
          return true;
        case R.id.menu_context_ban_user:
          handleBanUser(getListAdapter().getSelectedItems());
          return true;
        case R.id.menu_context_details:
          handleDisplayDetails(getSelectedMessageRecord());
          actionMode.finish();
          return true;
//        case R.id.menu_context_forward:
//          handleForwardMessage(getSelectedMessageRecord());
//          actionMode.finish();
//          return true;
        case R.id.menu_context_resend:
          handleResendMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_save_attachment:
          handleSaveAttachment((MediaMmsMessageRecord)getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_reply:
          handleReplyMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
      }

      return false;
    }
  }

  private static class ConversationDateHeader extends HeaderViewHolder {

    private final Animation animateIn;
    private final Animation animateOut;

    private boolean pendingHide = false;

    private ConversationDateHeader(Context context, TextView textView) {
      super(textView);
      this.animateIn  = AnimationUtils.loadAnimation(context, R.anim.slide_from_top);
      this.animateOut = AnimationUtils.loadAnimation(context, R.anim.slide_to_top);

      this.animateIn.setDuration(100);
      this.animateOut.setDuration(100);
    }

    public void show() {
      if (pendingHide) {
        pendingHide = false;
      } else {
        ViewUtil.animateIn(textView, animateIn);
      }
    }

    public void hide() {
      pendingHide = true;

      textView.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (pendingHide) {
            pendingHide = false;
            ViewUtil.animateOut(textView, animateOut, View.GONE);
          }
        }
      }, 400);
    }
  }
}
