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
package org.thoughtcrime.securesms.mms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Pair;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.MediaPreviewActivity;
import org.thoughtcrime.securesms.loki.views.MessageAudioView;
import org.thoughtcrime.securesms.components.DocumentView;
import org.thoughtcrime.securesms.components.RemovableEditableMediaView;
import org.thoughtcrime.securesms.components.ThumbnailView;
import org.session.libsignal.utilities.NoExternalStorageException;
import org.thoughtcrime.securesms.giph.ui.GiphyActivity;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.mediasend.MediaSendActivity;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.session.libsignal.utilities.ExternalStorageUtil;
import org.thoughtcrime.securesms.util.FileProviderUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.session.libsignal.utilities.guava.Optional;

import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.ThemeUtil;
import org.session.libsession.utilities.ViewUtil;
import org.session.libsession.utilities.Stub;
import org.session.libsignal.utilities.ListenableFuture;
import org.session.libsignal.utilities.ListenableFuture.Listener;
import org.session.libsignal.utilities.SettableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import network.loki.messenger.R;

import static android.provider.MediaStore.EXTRA_OUTPUT;


public class AttachmentManager {

  private final static String TAG = AttachmentManager.class.getSimpleName();

  private final @NonNull Context                    context;
  private final @NonNull Stub<View>                 attachmentViewStub;
  private final @NonNull AttachmentListener         attachmentListener;

  private RemovableEditableMediaView removableMediaView;
  private ThumbnailView              thumbnail;
  private MessageAudioView audioView;
  private DocumentView               documentView;

  private @NonNull  List<Uri>       garbage = new LinkedList<>();
  private @NonNull  Optional<Slide> slide   = Optional.absent();
  private @Nullable Uri             captureUri;

  public AttachmentManager(@NonNull Activity activity, @NonNull AttachmentListener listener) {
    this.context            = activity;
    this.attachmentListener = listener;
    this.attachmentViewStub = ViewUtil.findStubById(activity, R.id.attachment_editor_stub);
  }

  private void inflateStub() {
    if (!attachmentViewStub.resolved()) {
      View root = attachmentViewStub.get();

      this.thumbnail          = ViewUtil.findById(root, R.id.attachment_thumbnail);
      this.audioView          = ViewUtil.findById(root, R.id.attachment_audio);
      this.documentView       = ViewUtil.findById(root, R.id.attachment_document);
      this.removableMediaView = ViewUtil.findById(root, R.id.removable_media_view);

      removableMediaView.setRemoveClickListener(new RemoveButtonListener());
      thumbnail.setOnClickListener(new ThumbnailClickListener());
      documentView.getBackground().setColorFilter(ThemeUtil.getThemedColor(context, R.attr.conversation_item_bubble_background), PorterDuff.Mode.MULTIPLY);
    }
  }

  public void clear(@NonNull GlideRequests glideRequests, boolean animate) {
    if (attachmentViewStub.resolved()) {

      if (animate) {
        ViewUtil.fadeOut(attachmentViewStub.get(), 200).addListener(new Listener<Boolean>() {
          @Override
          public void onSuccess(Boolean result) {
            thumbnail.clear(glideRequests);
            attachmentViewStub.get().setVisibility(View.GONE);
            attachmentListener.onAttachmentChanged();
          }

          @Override
          public void onFailure(ExecutionException e) {
          }
        });
      } else {
        thumbnail.clear(glideRequests);
        attachmentViewStub.get().setVisibility(View.GONE);
        attachmentListener.onAttachmentChanged();
      }

      markGarbage(getSlideUri());
      slide = Optional.absent();

      audioView.cleanup();
    }
  }

  public void cleanup() {
    cleanup(captureUri);
    cleanup(getSlideUri());

    captureUri = null;
    slide      = Optional.absent();

    Iterator<Uri> iterator = garbage.listIterator();

    while (iterator.hasNext()) {
      cleanup(iterator.next());
      iterator.remove();
    }
  }

  private void cleanup(final @Nullable Uri uri) {
    if (uri != null && BlobProvider.isAuthority(uri)) {
      BlobProvider.getInstance().delete(context, uri);
    }
  }

  private void markGarbage(@Nullable Uri uri) {
    if (uri != null && BlobProvider.isAuthority(uri)) {
      Log.d(TAG, "Marking garbage that needs cleaning: " + uri);
      garbage.add(uri);
    }
  }

  private void setSlide(@NonNull Slide slide) {
    if (getSlideUri() != null) {
      cleanup(getSlideUri());
    }

    if (captureUri != null && !captureUri.equals(slide.getUri())) {
      cleanup(captureUri);
      captureUri = null;
    }

    this.slide = Optional.of(slide);
  }

  @SuppressLint("StaticFieldLeak")
  public ListenableFuture<Boolean> setMedia(@NonNull final GlideRequests glideRequests,
                                            @NonNull final Uri uri,
                                            @NonNull final MediaType mediaType,
                                            @NonNull final MediaConstraints constraints,
                                                     final int width,
                                                     final int height)
  {
    inflateStub();

    final SettableFuture<Boolean> result = new SettableFuture<>();

    new AsyncTask<Void, Void, Slide>() {
      @Override
      protected void onPreExecute() {
        thumbnail.clear(glideRequests);
        thumbnail.showProgressSpinner();
        attachmentViewStub.get().setVisibility(View.VISIBLE);
      }

      @Override
      protected @Nullable Slide doInBackground(Void... params) {
        try {
          if (PartAuthority.isLocalUri(uri)) {
            return getManuallyCalculatedSlideInfo(uri, width, height);
          } else {
            Slide result = getContentResolverSlideInfo(uri, width, height);

            if (result == null) return getManuallyCalculatedSlideInfo(uri, width, height);
            else                return result;
          }
        } catch (IOException e) {
          Log.w(TAG, e);
          return null;
        }
      }

      @Override
      protected void onPostExecute(@Nullable final Slide slide) {
        if (slide == null) {
          attachmentViewStub.get().setVisibility(View.GONE);
          Toast.makeText(context,
                         R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                         Toast.LENGTH_SHORT).show();
          result.set(false);
        } else if (!areConstraintsSatisfied(context, slide, constraints)) {
          attachmentViewStub.get().setVisibility(View.GONE);
          Toast.makeText(context,
                         R.string.ConversationActivity_attachment_exceeds_size_limits,
                         Toast.LENGTH_SHORT).show();
          result.set(false);
        } else {
          setSlide(slide);
          attachmentViewStub.get().setVisibility(View.VISIBLE);

          if (slide.hasAudio()) {
            audioView.setAudio((AudioSlide) slide, false);
            removableMediaView.display(audioView, false);
            result.set(true);
          } else if (slide.hasDocument()) {
            documentView.setDocument((DocumentSlide) slide, false);
            removableMediaView.display(documentView, false);
            result.set(true);
          } else {
            Attachment attachment = slide.asAttachment();
            result.deferTo(thumbnail.setImageResource(glideRequests, slide, false, true, attachment.getWidth(), attachment.getHeight()));
            removableMediaView.display(thumbnail, mediaType == MediaType.IMAGE);
          }

          attachmentListener.onAttachmentChanged();
        }
      }

      private @Nullable Slide getContentResolverSlideInfo(Uri uri, int width, int height) {
        Cursor cursor = null;
        long   start  = System.currentTimeMillis();

        try {
          cursor = context.getContentResolver().query(uri, null, null, null, null);

          if (cursor != null && cursor.moveToFirst()) {
            String fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
            long   fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
            String mimeType = context.getContentResolver().getType(uri);

            if (width == 0 || height == 0) {
              Pair<Integer, Integer> dimens = MediaUtil.getDimensions(context, mimeType, uri);
              width  = dimens.first;
              height = dimens.second;
            }

            Log.d(TAG, "remote slide with size " + fileSize + " took " + (System.currentTimeMillis() - start) + "ms");
            return mediaType.createSlide(context, uri, fileName, mimeType, fileSize, width, height);
          }
        } finally {
          if (cursor != null) cursor.close();
        }

        return null;
      }

      private @NonNull Slide getManuallyCalculatedSlideInfo(Uri uri, int width, int height) throws IOException {
        long start      = System.currentTimeMillis();
        Long mediaSize  = null;
        String fileName = null;
        String mimeType = null;

        if (PartAuthority.isLocalUri(uri)) {
          mediaSize = PartAuthority.getAttachmentSize(context, uri);
          fileName  = PartAuthority.getAttachmentFileName(context, uri);
          mimeType  = PartAuthority.getAttachmentContentType(context, uri);
        }

        if (mediaSize == null) {
          mediaSize = MediaUtil.getMediaSize(context, uri);
        }

        if (mimeType == null) {
          mimeType = MediaUtil.getMimeType(context, uri);
        }

        if (width == 0 || height == 0) {
          Pair<Integer, Integer> dimens = MediaUtil.getDimensions(context, mimeType, uri);
          width  = dimens.first;
          height = dimens.second;
        }

        Log.d(TAG, "local slide with size " + mediaSize + " took " + (System.currentTimeMillis() - start) + "ms");
        return mediaType.createSlide(context, uri, fileName, mimeType, mediaSize, width, height);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    return result;
  }

  public boolean isAttachmentPresent() {
    return attachmentViewStub.resolved() && attachmentViewStub.get().getVisibility() == View.VISIBLE;
  }

  public @NonNull SlideDeck buildSlideDeck() {
    SlideDeck deck = new SlideDeck();
    if (slide.isPresent()) deck.addSlide(slide.get());
    return deck;
  }

  public static void selectDocument(Activity activity, int requestCode) {
    selectMediaType(activity, "*/*", null, requestCode);
  }

  public static void selectGallery(Activity activity, int requestCode, @NonNull Recipient recipient, @NonNull String body) {
    Permissions.with(activity)
               .request(Manifest.permission.READ_EXTERNAL_STORAGE)
               .withPermanentDenialDialog(activity.getString(R.string.AttachmentManager_signal_requires_the_external_storage_permission_in_order_to_attach_photos_videos_or_audio))
               .onAllGranted(() -> activity.startActivityForResult(MediaSendActivity.buildGalleryIntent(activity, recipient, body), requestCode))
               .execute();
  }

  public static void selectAudio(Activity activity, int requestCode) {
    selectMediaType(activity, "audio/*", null, requestCode);
  }

  public static void selectContactInfo(Activity activity, int requestCode) {
    Permissions.with(activity)
               .request(Manifest.permission.WRITE_CONTACTS)
               .withPermanentDenialDialog(activity.getString(R.string.AttachmentManager_signal_requires_contacts_permission_in_order_to_attach_contact_information))
               .onAllGranted(() -> {
                 Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
                 activity.startActivityForResult(intent, requestCode);
               })
               .execute();
  }

  public static void selectLocation(Activity activity, int requestCode) {
    /* Loki - Enable again once we have location sharing
    Permissions.with(activity)
               .request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
               .withPermanentDenialDialog(activity.getString(R.string.AttachmentManager_signal_requires_location_information_in_order_to_attach_a_location))
               .onAllGranted(() -> {
                 try {
                   activity.startActivityForResult(new PlacePicker.IntentBuilder().build(activity), requestCode);
                 } catch (GooglePlayServicesRepairableException | GooglePlayServicesNotAvailableException e) {
                   Log.w(TAG, e);
                 }
               })
               .execute();
     */
  }

  public static void selectGif(Activity activity, int requestCode) {
    Intent intent = new Intent(activity, GiphyActivity.class);
    intent.putExtra(GiphyActivity.EXTRA_IS_MMS, false);
    activity.startActivityForResult(intent, requestCode);
  }

  private @Nullable Uri getSlideUri() {
    return slide.isPresent() ? slide.get().getUri() : null;
  }

  public @Nullable Uri getCaptureUri() {
    return captureUri;
  }

  public void capturePhoto(Activity activity, int requestCode) {
    Permissions.with(activity)
            .request(Manifest.permission.CAMERA)
            .withPermanentDenialDialog(activity.getString(R.string.AttachmentManager_signal_requires_the_camera_permission_in_order_to_take_photos_but_it_has_been_permanently_denied))
            .onAllGranted(() -> {
              try {
                File captureFile = File.createTempFile(
                        "conversation-capture",
                        ".jpg",
                        ExternalStorageUtil.getImageDir(activity));
                Uri captureUri = FileProviderUtil.getUriFor(context, captureFile);
                Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                captureIntent.putExtra(EXTRA_OUTPUT, captureUri);
                captureIntent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (captureIntent.resolveActivity(activity.getPackageManager()) != null) {
                  Log.d(TAG, "captureUri path is " + captureUri.getPath());
                  this.captureUri = captureUri;
                  activity.startActivityForResult(captureIntent, requestCode);
                }
              } catch (IOException | NoExternalStorageException e) {
                throw new RuntimeException("Error creating image capture intent.", e);
              }
            })
            .execute();
  }

  private static void selectMediaType(Activity activity, @NonNull String type, @Nullable String[] extraMimeType, int requestCode) {
    final Intent intent = new Intent();
    intent.setType(type);

    if (extraMimeType != null) {
      intent.putExtra(Intent.EXTRA_MIME_TYPES, extraMimeType);
    }

    intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
    try {
      activity.startActivityForResult(intent, requestCode);
      return;
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, "couldn't complete ACTION_OPEN_DOCUMENT, no activity found. falling back.");
    }

    intent.setAction(Intent.ACTION_GET_CONTENT);

    try {
      activity.startActivityForResult(intent, requestCode);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, "couldn't complete ACTION_GET_CONTENT intent, no activity found. falling back.");
      Toast.makeText(activity, R.string.AttachmentManager_cant_open_media_selection, Toast.LENGTH_LONG).show();
    }
  }

  private boolean areConstraintsSatisfied(final @NonNull  Context context,
                                          final @Nullable Slide slide,
                                          final @NonNull  MediaConstraints constraints)
  {
   return slide == null                                          ||
          constraints.isSatisfied(context, slide.asAttachment()) ||
          constraints.canResize(slide.asAttachment());
  }

  private void previewImageDraft(final @NonNull Slide slide) {
    if (MediaPreviewActivity.isContentTypeSupported(slide.getContentType()) && slide.getUri() != null) {
      Intent intent = new Intent(context, MediaPreviewActivity.class);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.putExtra(MediaPreviewActivity.SIZE_EXTRA, slide.asAttachment().getSize());
      intent.putExtra(MediaPreviewActivity.CAPTION_EXTRA, slide.getCaption().orNull());
      intent.putExtra(MediaPreviewActivity.OUTGOING_EXTRA, true);
      intent.setDataAndType(slide.getUri(), slide.getContentType());

      context.startActivity(intent);
    }
  }

  private class ThumbnailClickListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      if (slide.isPresent()) previewImageDraft(slide.get());
    }
  }

  private class RemoveButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      cleanup();
      clear(GlideApp.with(context.getApplicationContext()), true);
    }
  }

  public interface AttachmentListener {
    void onAttachmentChanged();
  }

  public enum MediaType {
    IMAGE, GIF, AUDIO, VIDEO, DOCUMENT, VCARD;

    public @NonNull Slide createSlide(@NonNull  Context context,
                                      @NonNull  Uri     uri,
                                      @Nullable String fileName,
                                      @Nullable String mimeType,
                                                long    dataSize,
                                                int     width,
                                                int     height)
    {
      if (mimeType == null) {
        mimeType = "application/octet-stream";
      }

      switch (this) {
      case IMAGE:    return new ImageSlide(context, uri, dataSize, width, height);
      case GIF:      return new GifSlide(context, uri, dataSize, width, height);
      case AUDIO:    return new AudioSlide(context, uri, dataSize, false);
      case VIDEO:    return new VideoSlide(context, uri, dataSize);
      case VCARD:
      case DOCUMENT: return new DocumentSlide(context, uri, mimeType, dataSize, fileName);
      default:       throw  new AssertionError("unrecognized enum");
      }
    }

    public static @Nullable MediaType from(final @Nullable String mimeType) {
      if (TextUtils.isEmpty(mimeType))     return null;
      if (MediaUtil.isGif(mimeType))       return GIF;
      if (MediaUtil.isImageType(mimeType)) return IMAGE;
      if (MediaUtil.isAudioType(mimeType)) return AUDIO;
      if (MediaUtil.isVideoType(mimeType)) return VIDEO;
      if (MediaUtil.isVcard(mimeType))     return VCARD;

      return DOCUMENT;
    }

  }
}
