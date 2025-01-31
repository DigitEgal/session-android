/*
 * Copyright (C) 2011 Whisper Systems
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

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.BindableConversationItem;
import org.thoughtcrime.securesms.conversation.ConversationAdapter.HeaderViewHolder;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.FastCursorRecyclerViewAdapter;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.session.libsession.utilities.recipients.Recipient;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.LRUCache;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;
import org.session.libsignal.utilities.guava.Optional;

import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment;
import org.session.libsession.utilities.Conversions;
import org.session.libsession.utilities.ViewUtil;
import org.session.libsession.utilities.Util;

import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import network.loki.messenger.R;

/**
 * A cursor adapter for a conversation thread.  Ultimately
 * used by ComposeMessageActivity to display a conversation
 * thread in a ListActivity.
 *
 * @author Moxie Marlinspike
 *
 */
public class ConversationAdapter <V extends View & BindableConversationItem>
    extends FastCursorRecyclerViewAdapter<ConversationAdapter.ViewHolder, MessageRecord>
  implements StickyHeaderDecoration.StickyHeaderAdapter<HeaderViewHolder>
{

  private static final int MAX_CACHE_SIZE = 1000;
  private static final String TAG = ConversationAdapter.class.getSimpleName();
  private final Map<String,SoftReference<MessageRecord>> messageRecordCache =
      Collections.synchronizedMap(new LRUCache<String, SoftReference<MessageRecord>>(MAX_CACHE_SIZE));
  private final SparseArray<String> positionToCacheRef = new SparseArray<>();

  private static final int MESSAGE_TYPE_OUTGOING            = 0;
  private static final int MESSAGE_TYPE_INCOMING            = 1;
  private static final int MESSAGE_TYPE_UPDATE              = 2;
  private static final int MESSAGE_TYPE_AUDIO_OUTGOING      = 3;
  private static final int MESSAGE_TYPE_AUDIO_INCOMING      = 4;
  private static final int MESSAGE_TYPE_THUMBNAIL_OUTGOING  = 5;
  private static final int MESSAGE_TYPE_THUMBNAIL_INCOMING  = 6;
  private static final int MESSAGE_TYPE_DOCUMENT_OUTGOING   = 7;
  private static final int MESSAGE_TYPE_DOCUMENT_INCOMING   = 8;
  private static final int MESSAGE_TYPE_INVITATION_OUTGOING = 9;
  private static final int MESSAGE_TYPE_INVITATION_INCOMING = 10;

  private final Set<MessageRecord> batchSelected = Collections.synchronizedSet(new HashSet<MessageRecord>());

  private final @Nullable ItemClickListener clickListener;
  private final @NonNull
  GlideRequests glideRequests;
  private final @NonNull  Locale            locale;
  private final @NonNull  Recipient         recipient;
  private final @NonNull  MmsSmsDatabase    db;
  private final @NonNull  LayoutInflater    inflater;
  private final @NonNull  Calendar          calendar;
  private final @NonNull  MessageDigest     digest;

  private MessageRecord recordToPulseHighlight;
  private String        searchQuery;

  protected static class ViewHolder extends RecyclerView.ViewHolder {
    public <V extends View & BindableConversationItem> ViewHolder(final @NonNull V itemView) {
      super(itemView);
    }

    @SuppressWarnings("unchecked")
    public <V extends View & BindableConversationItem> V getView() {
      return (V)itemView;
    }
  }


  static class HeaderViewHolder extends RecyclerView.ViewHolder {
    TextView textView;

    HeaderViewHolder(View itemView) {
      super(itemView);
      textView = ViewUtil.findById(itemView, R.id.text);
    }

    HeaderViewHolder(TextView textView) {
      super(textView);
      this.textView = textView;
    }

    public void setText(CharSequence text) {
      textView.setText(text);
    }
  }


  interface ItemClickListener extends BindableConversationItem.EventListener {
    void onItemClick(MessageRecord item);
    void onItemLongClick(MessageRecord item);
  }

  @SuppressWarnings("ConstantConditions")
  @VisibleForTesting
  ConversationAdapter(Context context, Cursor cursor) {
    super(context, cursor);
    try {
      this.glideRequests = null;
      this.locale        = null;
      this.clickListener = null;
      this.recipient     = null;
      this.inflater      = null;
      this.db            = null;
      this.calendar      = null;
      this.digest        = MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException nsae) {
      throw new AssertionError("SHA1 isn't supported!");
    }
  }

  public ConversationAdapter(@NonNull Context context,
                             @NonNull GlideRequests glideRequests,
                             @NonNull Locale locale,
                             @Nullable ItemClickListener clickListener,
                             @Nullable Cursor cursor,
                             @NonNull Recipient recipient)
  {
    super(context, cursor);

    try {
      this.glideRequests = glideRequests;
      this.locale        = locale;
      this.clickListener = clickListener;
      this.recipient     = recipient;
      this.inflater      = LayoutInflater.from(context);
      this.db            = DatabaseFactory.getMmsSmsDatabase(context);
      this.calendar      = Calendar.getInstance();
      this.digest        = MessageDigest.getInstance("SHA1");

      setHasStableIds(true);
    } catch (NoSuchAlgorithmException nsae) {
      throw new AssertionError("SHA1 isn't supported!");
    }
  }

  @Override
  public void changeCursor(Cursor cursor) {
    messageRecordCache.clear();
    positionToCacheRef.clear();
    super.cleanFastRecords();
    super.changeCursor(cursor);
  }

  @Override
  protected void onBindItemViewHolder(ViewHolder viewHolder, @NonNull MessageRecord messageRecord) {
    int           adapterPosition = viewHolder.getAdapterPosition();

    String prevCachedId = positionToCacheRef.get(adapterPosition + 1,null);
    String nextCachedId = positionToCacheRef.get(adapterPosition - 1, null);

    MessageRecord previousRecord = null;
    if (adapterPosition < getItemCount() - 1 && !isFooterPosition(adapterPosition + 1)) {
      if (prevCachedId != null && messageRecordCache.containsKey(prevCachedId)) {
        SoftReference<MessageRecord> prevSoftRecord = messageRecordCache.get(prevCachedId);
        MessageRecord prevCachedRecord = prevSoftRecord.get();
        if (prevCachedRecord != null) {
          previousRecord = prevCachedRecord;
        } else {
          previousRecord = getRecordForPositionOrThrow(adapterPosition + 1);
        }
      } else {
        previousRecord = getRecordForPositionOrThrow(adapterPosition + 1);
      }
    }

    MessageRecord nextRecord = null;
    if (adapterPosition > 0 && !isHeaderPosition(adapterPosition - 1)) {
      if (nextCachedId != null && messageRecordCache.containsKey(nextCachedId)) {
        SoftReference<MessageRecord> nextSoftRecord = messageRecordCache.get(nextCachedId);
        MessageRecord nextCachedRecord = nextSoftRecord.get();
        if (nextCachedRecord != null) {
          nextRecord = nextCachedRecord;
        } else {
          nextRecord = getRecordForPositionOrThrow(adapterPosition - 1);
        }
      } else {
        nextRecord = getRecordForPositionOrThrow(adapterPosition - 1);
      }
    }

    viewHolder.getView().bind(messageRecord,
                          Optional.fromNullable(previousRecord),
                          Optional.fromNullable(nextRecord),
                          glideRequests,
                          locale,
                          batchSelected,
                          recipient,
                          searchQuery,
                          messageRecord == recordToPulseHighlight);

    if (messageRecord == recordToPulseHighlight) {
      recordToPulseHighlight = null;
    }
  }

  @Override
  public ViewHolder onCreateItemViewHolder(ViewGroup parent, int viewType) {
    long start = System.currentTimeMillis();
    final V itemView = ViewUtil.inflate(inflater, parent, getLayoutForViewType(viewType));
    itemView.setOnClickListener(view -> {
      if (clickListener != null) {
        clickListener.onItemClick(itemView.getMessageRecord());
      }
    });
    itemView.setOnLongClickListener(view -> {
      if (clickListener != null) {
        clickListener.onItemLongClick(itemView.getMessageRecord());
      }
      return true;
    });
    itemView.setEventListener(clickListener);
    Log.d(TAG, "Inflate time: " + (System.currentTimeMillis() - start));
    return new ViewHolder(itemView);
  }

  @Override
  public void onItemViewRecycled(ViewHolder holder) {
    holder.getView().unbind();
  }

  private @LayoutRes int getLayoutForViewType(int viewType) {
    switch (viewType) {
      case MESSAGE_TYPE_AUDIO_OUTGOING:
      case MESSAGE_TYPE_THUMBNAIL_OUTGOING:
      case MESSAGE_TYPE_DOCUMENT_OUTGOING:
      case MESSAGE_TYPE_INVITATION_OUTGOING:
      case MESSAGE_TYPE_OUTGOING:        return R.layout.conversation_item_sent;
      case MESSAGE_TYPE_AUDIO_INCOMING:
      case MESSAGE_TYPE_THUMBNAIL_INCOMING:
      case MESSAGE_TYPE_DOCUMENT_INCOMING:
      case MESSAGE_TYPE_INVITATION_INCOMING:
      case MESSAGE_TYPE_INCOMING:        return R.layout.conversation_item_received;
      case MESSAGE_TYPE_UPDATE:          return R.layout.conversation_item_update;
      default: throw new IllegalArgumentException("unsupported item view type given to ConversationAdapter");
    }
  }

  @Override
  public int getItemViewType(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isUpdate()) {
      return MESSAGE_TYPE_UPDATE;
    } else if (messageRecord.isOpenGroupInvitation()) {
      if (messageRecord.isOutgoing()) return MESSAGE_TYPE_INVITATION_OUTGOING;
      else                            return MESSAGE_TYPE_INVITATION_INCOMING;
    } else if (hasAudio(messageRecord)) {
      if (messageRecord.isOutgoing()) return MESSAGE_TYPE_AUDIO_OUTGOING;
      else                            return MESSAGE_TYPE_AUDIO_INCOMING;
    } else if (hasDocument(messageRecord)) {
      if (messageRecord.isOutgoing()) return MESSAGE_TYPE_DOCUMENT_OUTGOING;
      else                            return MESSAGE_TYPE_DOCUMENT_INCOMING;
    } else if (hasThumbnail(messageRecord)) {
      if (messageRecord.isOutgoing()) return MESSAGE_TYPE_THUMBNAIL_OUTGOING;
      else                            return MESSAGE_TYPE_THUMBNAIL_INCOMING;
    } else if (messageRecord.isOutgoing()) {
      return MESSAGE_TYPE_OUTGOING;
    } else {
      return MESSAGE_TYPE_INCOMING;
    }
  }

  @Override
  protected boolean isRecordForId(@NonNull MessageRecord record, long id) {
    return record.getId() == id;
  }

  @Override
  public long getItemId(@NonNull Cursor cursor) {
    List<DatabaseAttachment> attachments        = DatabaseFactory.getAttachmentDatabase(getContext()).getAttachment(cursor);
    List<DatabaseAttachment> messageAttachments = Stream.of(attachments).filterNot(DatabaseAttachment::isQuote).toList();

    if (messageAttachments.size() > 0 && messageAttachments.get(0).getFastPreflightId() != null) {
      return Long.valueOf(messageAttachments.get(0).getFastPreflightId());
    }

    final String unique = cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsColumns.UNIQUE_ROW_ID));
    final byte[] bytes  = digest.digest(unique.getBytes());
    return Conversions.byteArrayToLong(bytes);
  }

  @Override
  protected long getItemId(@NonNull MessageRecord record) {
    if (record.isOutgoing() && record.isMms()) {
      MmsMessageRecord mmsRecord = (MmsMessageRecord) record;
      SlideDeck        slideDeck = mmsRecord.getSlideDeck();

      if (slideDeck.getThumbnailSlide() != null && slideDeck.getThumbnailSlide().getFastPreflightId() != null) {
        return Long.valueOf(slideDeck.getThumbnailSlide().getFastPreflightId());
      }
    }

    return record.getId();
  }

  @Override
  protected MessageRecord getRecordFromCursor(@NonNull Cursor cursor) {
    long   messageId = cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.ID));
    String type      = cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsDatabase.TRANSPORT));

    final SoftReference<MessageRecord> reference = messageRecordCache.get(type + messageId);
    if (reference != null) {
      final MessageRecord record = reference.get();
      if (record != null) return record;
    }

    final MessageRecord messageRecord = db.readerFor(cursor).getCurrent();
    messageRecordCache.put(type + messageId, new SoftReference<>(messageRecord));

    return messageRecord;
  }

  public void close() {
    getCursor().close();
  }

  public int findLastSeenPosition(long lastSeen) {
    if (lastSeen <= 0)     return -1;
    if (!isActiveCursor()) return -1;

    int count = getItemCount() - (hasFooterView() ? 1 : 0);

    for (int i=(hasHeaderView() ? 1 : 0);i<count;i++) {
      MessageRecord messageRecord = getRecordForPositionOrThrow(i);

      if (messageRecord.isOutgoing() || messageRecord.getDateReceived() <= lastSeen) {
        return i;
      }
    }

    return -1;
  }

  public void toggleSelection(MessageRecord messageRecord) {
    if (!batchSelected.remove(messageRecord)) {
      batchSelected.add(messageRecord);
    }
  }

  public void clearSelection() {
    batchSelected.clear();
  }

  public Set<MessageRecord> getSelectedItems() {
    return Collections.unmodifiableSet(new HashSet<>(batchSelected));
  }

  public void pulseHighlightItem(int position) {
    if (position < getItemCount()) {
      recordToPulseHighlight = getRecordForPositionOrThrow(position);
      notifyItemChanged(position);
    }
  }

  public void onSearchQueryUpdated(@Nullable String query) {
    this.searchQuery = query;
    notifyDataSetChanged();
  }

  private boolean hasAudio(MessageRecord messageRecord) {
    return messageRecord.isMms() && ((MmsMessageRecord)messageRecord).getSlideDeck().getAudioSlide() != null;
  }

  private boolean hasDocument(MessageRecord messageRecord) {
    return messageRecord.isMms() && ((MmsMessageRecord)messageRecord).getSlideDeck().getDocumentSlide() != null;
  }

  private boolean hasThumbnail(MessageRecord messageRecord) {
    return messageRecord.isMms() && ((MmsMessageRecord)messageRecord).getSlideDeck().getThumbnailSlide() != null;
  }

  @Override
  public long getHeaderId(int position) {
    if (!isActiveCursor())          return -1;
    if (isHeaderPosition(position)) return -1;
    if (isFooterPosition(position)) return -1;
    if (position >= getItemCount()) return -1;
    if (position < 0)               return -1;

    MessageRecord record = getRecordForPositionOrThrow(position);
    if (record.getRecipient().getAddress().isOpenGroup()) {
      calendar.setTime(new Date(record.getDateReceived()));
    } else {
      calendar.setTime(new Date(record.getDateSent()));
    }
    return Util.hashCode(calendar.get(Calendar.YEAR), calendar.get(Calendar.DAY_OF_YEAR));
  }

  public long getReceivedTimestamp(int position) {
    if (!isActiveCursor())          return 0;
    if (isHeaderPosition(position)) return 0;
    if (isFooterPosition(position)) return 0;
    if (position >= getItemCount()) return 0;
    if (position < 0)               return 0;

    MessageRecord messageRecord = getRecordForPositionOrThrow(position);

    if (messageRecord.isOutgoing()) return 0;
    else                            return messageRecord.getDateReceived();
  }

  @Override
  public HeaderViewHolder onCreateHeaderViewHolder(ViewGroup parent) {
    return new HeaderViewHolder(LayoutInflater.from(getContext()).inflate(R.layout.conversation_item_header, parent, false));
  }

  public HeaderViewHolder onCreateLastSeenViewHolder(ViewGroup parent) {
    return new HeaderViewHolder(LayoutInflater.from(getContext()).inflate(R.layout.conversation_item_last_seen, parent, false));
  }

  @Override
  public void onBindHeaderViewHolder(HeaderViewHolder viewHolder, int position) {
    MessageRecord messageRecord = getRecordForPositionOrThrow(position);
    long timestamp = messageRecord.getDateReceived();
    if (recipient.getAddress().isOpenGroup()) { timestamp = messageRecord.getTimestamp(); }
    viewHolder.setText(DateUtils.getRelativeDate(getContext(), locale, timestamp));
  }

  public void onBindLastSeenViewHolder(HeaderViewHolder viewHolder, int position) {
    viewHolder.setText(getContext().getResources().getQuantityString(R.plurals.ConversationAdapter_n_unread_messages, (position + 1), (position + 1)));
  }

  static class LastSeenHeader extends StickyHeaderDecoration {

    private final ConversationAdapter adapter;
    private final long                lastSeenTimestamp;

    LastSeenHeader(ConversationAdapter adapter, long lastSeenTimestamp) {
      super(adapter, false, false);
      this.adapter           = adapter;
      this.lastSeenTimestamp = lastSeenTimestamp;
    }

    @Override
    protected boolean hasHeader(RecyclerView parent, StickyHeaderAdapter stickyAdapter, int position) {
      if (!adapter.isActiveCursor()) {
        return false;
      }

      if (lastSeenTimestamp <= 0) {
        return false;
      }

      long currentRecordTimestamp  = adapter.getReceivedTimestamp(position);
      long previousRecordTimestamp = adapter.getReceivedTimestamp(position + 1);

      return currentRecordTimestamp > lastSeenTimestamp && previousRecordTimestamp < lastSeenTimestamp;
    }

    @Override
    protected int getHeaderTop(RecyclerView parent, View child, View header, int adapterPos, int layoutPos) {
      return parent.getLayoutManager().getDecoratedTop(child);
    }

    @Override
    protected HeaderViewHolder getHeader(RecyclerView parent, StickyHeaderAdapter stickyAdapter, int position) {
      HeaderViewHolder viewHolder = adapter.onCreateLastSeenViewHolder(parent);
      adapter.onBindLastSeenViewHolder(viewHolder, position);

      int widthSpec  = View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY);
      int heightSpec = View.MeasureSpec.makeMeasureSpec(parent.getHeight(), View.MeasureSpec.UNSPECIFIED);

      int childWidth  = ViewGroup.getChildMeasureSpec(widthSpec, parent.getPaddingLeft() + parent.getPaddingRight(), viewHolder.itemView.getLayoutParams().width);
      int childHeight = ViewGroup.getChildMeasureSpec(heightSpec, parent.getPaddingTop() + parent.getPaddingBottom(), viewHolder.itemView.getLayoutParams().height);

      viewHolder.itemView.measure(childWidth, childHeight);
      viewHolder.itemView.layout(0, 0, viewHolder.itemView.getMeasuredWidth(), viewHolder.itemView.getMeasuredHeight());

      return viewHolder;
    }
  }

}

