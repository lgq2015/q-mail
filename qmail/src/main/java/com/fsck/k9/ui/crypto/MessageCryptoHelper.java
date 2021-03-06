package com.fsck.k9.ui.crypto;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.fsck.k9.QMail;
import com.fsck.k9.autocrypt.AutocryptOperations;
import com.fsck.k9.crypto.MessageCryptoStructureDetector;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Multipart;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.internet.MessageExtractor;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.SizeAware;
import com.fsck.k9.mail.internet.TextBody;
import com.fsck.k9.mailstore.CryptoResultAnnotation;
import com.fsck.k9.mailstore.CryptoResultAnnotation.CryptoError;
import com.fsck.k9.mailstore.CryptoResultAnnotation.CryptoProviderType;
import com.fsck.k9.mailstore.MessageHelper;
import com.fsck.k9.mailstore.MimePartStreamParser;
import com.fsck.k9.mailstore.util.FileFactory;
import com.fsck.k9.provider.DecryptedFileProvider;
import org.apache.commons.io.IOUtils;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpDecryptionResult;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpApi.IOpenPgpCallback;
import org.openintents.openpgp.util.OpenPgpApi.IOpenPgpSinkResultCallback;
import org.openintents.openpgp.util.OpenPgpApi.OpenPgpDataSink;
import org.openintents.openpgp.util.OpenPgpApi.OpenPgpDataSource;
import org.openintents.openpgp.util.OpenPgpServiceConnection;
import org.openintents.smime.ISMimeService2;
import org.openintents.smime.SMimeDecryptionResult;
import org.openintents.smime.SMimeError;
import org.openintents.smime.SMimeSignatureResult;
import org.openintents.smime.util.SMimeApi;
import org.openintents.smime.util.SMimeApi.ISMimeSinkResultCallback;
import org.openintents.smime.util.SMimeApi.SMimeDataSink;
import org.openintents.smime.util.SMimeApi.SMimeDataSource;
import org.openintents.smime.util.SMimeServiceConnection;
import timber.log.Timber;


public class MessageCryptoHelper {
    private static final int INVALID_OPENPGP_RESULT_CODE = -1;
    private static final int INVALID_SMIME_RESULT_CODE = -1;
    private static final MimeBodyPart NO_REPLACEMENT_PART = null;
    private static final int REQUEST_CODE_USER_INTERACTION = 124;


    private final Context context;
    private final String openPgpProviderPackage;
    private final String sMimeProviderPackage;
    private final boolean hasOpenPgpProvider;
    private final boolean hasSMimeProvider;
    private final AutocryptOperations autocryptOperations;
    private final Object callbackLock = new Object();
    private final Deque<CryptoPart> partsToProcess = new ArrayDeque<>();

    @Nullable
    private MessageCryptoCallback callback;

    private Message currentMessage;
    private OpenPgpDecryptionResult cachedOpenPgpDecryptionResult;
    private SMimeDecryptionResult cachedSMimeDecryptionResult;
    private MessageCryptoAnnotations queuedResult;
    private PendingIntent queuedPendingIntent;


    private MessageCryptoAnnotations messageAnnotations;
    private CryptoPart currentCryptoPart;
    private Intent currentCryptoResult;
    private Intent userInteractionResultIntent;
    private State state;
    private OpenPgpApi.CancelableBackgroundOperation cancelableOpenPgpBackgroundOperation;
    private SMimeApi.CancelableBackgroundOperation cancelableSMimeBackgroundOperation;
    private boolean isCancelled;
    private boolean processSignedOnly;

    private OpenPgpApi openPgpApi;
    private OpenPgpServiceConnection openPgpServiceConnection;
    private OpenPgpApiFactory openPgpApiFactory;
    private SMimeApi sMimeApi;
    private SMimeServiceConnection sMimeServiceConnection;
    private SMimeApiFactory sMimeApiFactory;


    public MessageCryptoHelper(Context context, OpenPgpApiFactory openPgpApiFactory, SMimeApiFactory sMimeApiFactory,
            AutocryptOperations autocryptOperations) {
        this.context = context.getApplicationContext();
        this.autocryptOperations = autocryptOperations;
        this.openPgpApiFactory = openPgpApiFactory;
        this.sMimeApiFactory = sMimeApiFactory;
        hasOpenPgpProvider = QMail.isOpenPgpProviderConfigured();
        openPgpProviderPackage = QMail.getOpenPgpProvider();
        hasSMimeProvider = QMail.isSMimeProviderConfigured();
        sMimeProviderPackage = QMail.getSMimeProvider();
    }

    public boolean isConfiguredForOutdatedCryptoProvider() {
        return isConfiguredForOutdatedOpenPgpProvider() || isConfiguredForOutdatedSMimeProvider();
    }

    public boolean isConfiguredForOutdatedOpenPgpProvider() {
        return !openPgpProviderPackage.equals(QMail.getOpenPgpProvider());
    }

    public boolean isConfiguredForOutdatedSMimeProvider() {
        return !sMimeProviderPackage.equals(QMail.getSMimeProvider());
    }

    public void asyncStartOrResumeProcessingMessage(Message message, MessageCryptoCallback callback,
            OpenPgpDecryptionResult cachedOpenPgpDecryptionResult, SMimeDecryptionResult cachedSMimeDecryptionResult, boolean processSignedOnly) {
        if (this.currentMessage != null) {
            reattachCallback(message, callback);
            return;
        }

        this.messageAnnotations = new MessageCryptoAnnotations();
        this.state = State.START;
        this.currentMessage = message;
        this.cachedOpenPgpDecryptionResult = cachedOpenPgpDecryptionResult;
        this.cachedSMimeDecryptionResult = cachedSMimeDecryptionResult;
        this.callback = callback;
        this.processSignedOnly = processSignedOnly;

        nextStep();
    }

    private void findPartsForAutocryptPass() {
        boolean otherCryptoPerformed = !messageAnnotations.isEmpty();
        if (otherCryptoPerformed) {
            return;
        }

        if (hasOpenPgpProvider && autocryptOperations.hasAutocryptHeader(currentMessage)) {
            CryptoPart cryptoPart = new CryptoPart(CryptoProviderType.OPENPGP, CryptoPartType.PLAIN_AUTOCRYPT, currentMessage);
            partsToProcess.add(cryptoPart);
        }
    }

    private void findPartsForMultipartEncryptionPass() {
        List<Part> encryptedParts = MessageCryptoStructureDetector.findMultipartEncryptedParts(currentMessage);
        for (Part part : encryptedParts) {
            if (!MessageHelper.isCompletePartAvailable(part)) {
                addErrorAnnotation(part, CryptoError.OPENPGP_ENCRYPTED_BUT_INCOMPLETE, MessageHelper.createEmptyPart());
                continue;
            }
            if (MessageCryptoStructureDetector.isMultipartEncryptedOpenPgpProtocol(part)) {
                CryptoPart cryptoPart = new CryptoPart(CryptoProviderType.OPENPGP, CryptoPartType.PGP_ENCRYPTED, part);
                partsToProcess.add(cryptoPart);
                continue;
            }
            if (MessageCryptoStructureDetector.isMultipartEncryptedSMimeProtocol(part)) {
                CryptoPart cryptoPart = new CryptoPart(CryptoProviderType.SMIME, CryptoPartType.SMIME_ENCRYPTED, part);
                partsToProcess.add(cryptoPart);
                continue;
            }
            addErrorAnnotation(part, CryptoError.ENCRYPTED_BUT_UNSUPPORTED, MessageHelper.createEmptyPart());
        }
    }

    private void findPartsForMultipartSignaturePass() {
        List<Part> signedParts = MessageCryptoStructureDetector
                .findMultipartSignedParts(currentMessage, messageAnnotations);
        for (Part part : signedParts) {
            if (!processSignedOnly) {
                boolean isEncapsulatedSignature =
                        messageAnnotations.findKeyForAnnotationWithReplacementPart(part) != null;
                if (!isEncapsulatedSignature) {
                    continue;
                }
            }
            if (!MessageHelper.isCompletePartAvailable(part)) {
                MimeBodyPart replacementPart = getMultipartSignedContentPartIfAvailable(part);
                addErrorAnnotation(part, CryptoError.OPENPGP_SIGNED_BUT_INCOMPLETE, replacementPart);
                continue;
            }
            if (MessageCryptoStructureDetector.isMultipartSignedOpenPgpProtocol(part)) {
                CryptoPart cryptoPart = new CryptoPart(CryptoProviderType.OPENPGP, CryptoPartType.PGP_SIGNED, part);
                partsToProcess.add(cryptoPart);
                continue;
            }
            if (MessageCryptoStructureDetector.isMultipartSignedSMimeProtocol(part)) {
                CryptoPart cryptoPart = new CryptoPart(CryptoProviderType.SMIME, CryptoPartType.SMIME_SIGNED, part);
                partsToProcess.add(cryptoPart);
                continue;
            }
            MimeBodyPart replacementPart = getMultipartSignedContentPartIfAvailable(part);
            addErrorAnnotation(part, CryptoError.SIGNED_BUT_UNSUPPORTED, replacementPart);
        }
    }

    private void findPartsForPgpInlinePass() {
        List<Part> inlineParts = MessageCryptoStructureDetector.findPgpInlineParts(currentMessage);
        for (Part part : inlineParts) {
            if (!processSignedOnly && !MessageCryptoStructureDetector.isPartPgpInlineEncrypted(part)) {
                continue;
            }

            if (!currentMessage.getFlags().contains(Flag.X_DOWNLOADED_FULL)) {
                if (MessageCryptoStructureDetector.isPartPgpInlineEncrypted(part)) {
                    addErrorAnnotation(part, CryptoError.OPENPGP_ENCRYPTED_BUT_INCOMPLETE, NO_REPLACEMENT_PART);
                } else {
                    addErrorAnnotation(part, CryptoError.OPENPGP_SIGNED_BUT_INCOMPLETE, NO_REPLACEMENT_PART);
                }
                continue;
            }

            CryptoPart cryptoPart = new CryptoPart(CryptoProviderType.OPENPGP, CryptoPartType.PGP_INLINE, part);
            partsToProcess.add(cryptoPart);
        }
    }

    private void addErrorAnnotation(Part part, CryptoError error, MimeBodyPart replacementPart) {
        CryptoResultAnnotation annotation = CryptoResultAnnotation.createErrorAnnotation(error, replacementPart);
        messageAnnotations.put(part, annotation);
    }

    private void nextStep() {
        if (isCancelled) {
            return;
        }

        while (state != State.FINISHED && partsToProcess.isEmpty()) {
            findPartsForNextPass();
        }

        if (state == State.FINISHED) {
            callbackReturnResult();
            return;
        }

        if (hasOpenPgpProvider && !isBoundToOpenPgpProviderService()) {
            connectToOpenPgpProviderService();
            return;
        }

        if (hasSMimeProvider && !isBoundToSMimeProviderService()) {
            connectToSMimeProviderService();
            return;
        }

        currentCryptoPart = partsToProcess.peekFirst();
        if (currentCryptoPart.type == CryptoPartType.PLAIN_AUTOCRYPT) {
            processAutocryptHeaderForCurrentPart();
        } else {
            decryptOrVerifyCurrentPart();
        }
    }

    private boolean isBoundToOpenPgpProviderService() {
        return openPgpApi != null;
    }

    private boolean isBoundToSMimeProviderService() {
        return sMimeApi != null;
    }

    private void connectToOpenPgpProviderService() {
        openPgpServiceConnection = new OpenPgpServiceConnection(context, openPgpProviderPackage,
                new OpenPgpServiceConnection.OnBound() {

                    @Override
                    public void onBound(IOpenPgpService2 service) {
                        openPgpApi = openPgpApiFactory.createOpenPgpApi(context, service);

                        nextStep();
                    }

                    @Override
                    public void onError(Exception e) {
                        // TODO actually handle (hand to ui, offer retry?)
                        Timber.e(e, "Couldn't connect to OpenPgpService");
                    }
                });
        openPgpServiceConnection.bindToService();
    }

    private void connectToSMimeProviderService() {
        sMimeServiceConnection = new SMimeServiceConnection(context, sMimeProviderPackage,
                new SMimeServiceConnection.OnBound() {

                    @Override
                    public void onBound(ISMimeService2 service) {
                        sMimeApi = sMimeApiFactory.createSMimeApi(context, service);

                        nextStep();
                    }

                    @Override
                    public void onError(Exception e) {
                        // TODO actually handle (hand to ui, offer retry?)
                        Timber.e(e, "Couldn't connect to SMimeService");
                    }
                });
        sMimeServiceConnection.bindToService();
    }

    private void decryptOrVerifyCurrentPart() {
        Intent apiIntent = userInteractionResultIntent;
        userInteractionResultIntent = null;
        if (apiIntent == null) {
            apiIntent = getDecryptVerifyIntent();
        }
        decryptVerify(apiIntent);
    }

    @NonNull
    private Intent getDecryptVerifyIntent() {
        switch (currentCryptoPart.providerType) {
            case OPENPGP:
                return getOpenPgpDecryptVerifyIntent();
            case SMIME:
                return getSMimeDecryptVerifyIntent();
            default:
                throw new IllegalArgumentException();
        }
    }

    @NonNull
    private Intent getOpenPgpDecryptVerifyIntent() {
        Intent decryptIntent = new Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY);

        Address[] from = currentMessage.getFrom();
        if (from.length > 0) {
            decryptIntent.putExtra(OpenPgpApi.EXTRA_SENDER_ADDRESS, from[0].getAddress());
            // we add this here independently of the autocrypt peer update, to allow picking up signing keys as gossip
            decryptIntent.putExtra(OpenPgpApi.EXTRA_AUTOCRYPT_PEER_ID, from[0].getAddress());
        }
        autocryptOperations.addAutocryptPeerUpdateToIntentIfPresent(currentMessage, decryptIntent);

        decryptIntent.putExtra(OpenPgpApi.EXTRA_SUPPORT_OVERRIDE_CRYPTO_WARNING, true);
        decryptIntent.putExtra(OpenPgpApi.EXTRA_DECRYPTION_RESULT, cachedOpenPgpDecryptionResult);

        return decryptIntent;
    }

    @NonNull
    private Intent getSMimeDecryptVerifyIntent() {
        Intent decryptIntent = new Intent(SMimeApi.ACTION_DECRYPT_VERIFY);

        Address[] from = currentMessage.getFrom();
        if (from.length > 0) {
            decryptIntent.putExtra(SMimeApi.EXTRA_SENDER_ADDRESS, from[0].getAddress());
        }
        decryptIntent.putExtra(SMimeApi.EXTRA_SUPPORT_OVERRIDE_CRYPTO_WARNING, true);
        decryptIntent.putExtra(SMimeApi.EXTRA_DECRYPTION_RESULT, cachedSMimeDecryptionResult);

        return decryptIntent;
    }

    private void decryptVerify(Intent apiIntent) {
        try {
            CryptoPartType cryptoPartType = currentCryptoPart.type;
            switch (cryptoPartType) {
                case PGP_SIGNED: {
                    callAsyncOpenPgpDetachedVerify(apiIntent);
                    return;
                }
                case PGP_ENCRYPTED: {
                    callAsyncOpenPgpDecrypt(apiIntent);
                    return;
                }
                case PGP_INLINE: {
                    callAsyncOpenPgpInlineOperation(apiIntent);
                    return;
                }
                case SMIME_SIGNED: {
                    callAsyncSMimeDetachedVerify(apiIntent);
                    return;
                }
                case SMIME_ENCRYPTED: {
                    callAsyncSMimeDecrypt(apiIntent);
                    return;
                }
                case PLAIN_AUTOCRYPT:
                    throw new IllegalStateException("This part type must have been handled previously!");
            }

            throw new IllegalStateException("Unknown crypto part type: " + cryptoPartType);
        } catch (IOException e) {
            Timber.e(e, "IOException");
        } catch (MessagingException e) {
            Timber.e(e, "MessagingException");
        }
    }

    private void processAutocryptHeaderForCurrentPart() {
        Intent intent = new Intent(OpenPgpApi.ACTION_UPDATE_AUTOCRYPT_PEER);
        boolean hasInlineKeyData = autocryptOperations.addAutocryptPeerUpdateToIntentIfPresent(
                (Message) currentCryptoPart.part, intent);
        if (hasInlineKeyData) {
            Timber.d("Passing autocrypt data from plain mail to OpenPGP API");
            // We don't care about the result here, so we just call this fire-and-forget wait to minimize delay
            openPgpApi.executeApiAsync(intent, null, null, new IOpenPgpCallback() {
                @Override
                public void onReturn(Intent result) {
                    Timber.d("Autocrypt update OK!");
                }
            });
        }
        onCryptoFinished();
    }

    private void callAsyncOpenPgpInlineOperation(Intent intent) throws IOException {
        if (openPgpApi == null) {
            CryptoResultAnnotation annotation =
                    CryptoResultAnnotation.createErrorAnnotation(CryptoError.OPENPGP_ENCRYPTED_NO_PROVIDER, null);
            addCryptoResultAnnotationToMessage(annotation);
            onCryptoFinished();
            return;
        }

        OpenPgpDataSource dataSource = getDataSourceForOpenPgpEncryptedOrInlineData();
        OpenPgpDataSink<MimeBodyPart> dataSink = getDataSinkForOpenPgpDecryptedInlineData();

        cancelableOpenPgpBackgroundOperation = openPgpApi.executeApiAsync(intent, dataSource, dataSink,
                new IOpenPgpSinkResultCallback<MimeBodyPart>() {
            @Override
            public void onProgress(int current, int max) {
                Timber.d("received progress status: %d / %d", current, max);
                callbackProgress(current, max);
            }

            @Override
            public void onReturn(Intent result, MimeBodyPart bodyPart) {
                cancelableOpenPgpBackgroundOperation = null;
                currentCryptoResult = result;
                onCryptoOperationReturned(bodyPart);
            }
        });
    }

    public void cancelIfRunning() {
        detachCallback();
        isCancelled = true;
        if (cancelableOpenPgpBackgroundOperation != null) {
            cancelableOpenPgpBackgroundOperation.cancelOperation();
        }
        if (cancelableSMimeBackgroundOperation != null) {
            cancelableSMimeBackgroundOperation.cancelOperation();
        }
    }

    private void callAsyncOpenPgpDecrypt(Intent intent) throws IOException {
        if (openPgpApi == null) {
            CryptoResultAnnotation annotation =
                    CryptoResultAnnotation.createErrorAnnotation(CryptoError.OPENPGP_ENCRYPTED_NO_PROVIDER, null);
            addCryptoResultAnnotationToMessage(annotation);
            onCryptoFinished();
            return;
        }

        OpenPgpDataSource dataSource = getDataSourceForOpenPgpEncryptedOrInlineData();
        OpenPgpDataSink<MimeBodyPart> openPgpDataSink = getDataSinkForOpenPgpDecryptedData();

        cancelableOpenPgpBackgroundOperation = openPgpApi.executeApiAsync(intent, dataSource, openPgpDataSink,
                new IOpenPgpSinkResultCallback<MimeBodyPart>() {
            @Override
            public void onReturn(Intent result, MimeBodyPart decryptedPart) {
                cancelableOpenPgpBackgroundOperation = null;
                currentCryptoResult = result;
                onCryptoOperationReturned(decryptedPart);
            }

            @Override
            public void onProgress(int current, int max) {
                Timber.d("received progress status: %d / %d", current, max);
                callbackProgress(current, max);
            }
        });
    }

    private void callAsyncOpenPgpDetachedVerify(Intent intent) throws IOException, MessagingException {
        if (openPgpApi == null) {
            MimeBodyPart replacementPart = getMultipartSignedContentPartIfAvailable(currentCryptoPart.part);
            CryptoResultAnnotation annotation =
                    CryptoResultAnnotation.createErrorAnnotation(CryptoError.OPENPGP_SIGNED_NO_PROVIDER, replacementPart);
            addCryptoResultAnnotationToMessage(annotation);
            onCryptoFinished();
            return;
        }

        OpenPgpDataSource dataSource = getDataSourceForOpenPgpSignedData(currentCryptoPart.part);

        byte[] signatureData = MessageCryptoStructureDetector.getSignatureData(currentCryptoPart.part);
        intent.putExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE, signatureData);

        openPgpApi.executeApiAsync(intent, dataSource, new IOpenPgpSinkResultCallback<Void>() {
            @Override
            public void onReturn(Intent result, Void dummy) {
                cancelableOpenPgpBackgroundOperation = null;
                currentCryptoResult = result;
                onCryptoOperationReturned(null);
            }

            @Override
            public void onProgress(int current, int max) {
                Timber.d("received progress status: %d / %d", current, max);
                callbackProgress(current, max);
            }
        });
    }

    private void callAsyncSMimeDecrypt(Intent intent) throws IOException {
        if (sMimeApi == null) {
            CryptoResultAnnotation annotation =
                    CryptoResultAnnotation.createErrorAnnotation(CryptoError.SMIME_ENCRYPTED_NO_PROVIDER, null);
            addCryptoResultAnnotationToMessage(annotation);
            onCryptoFinished();
            return;
        }

        SMimeDataSource dataSource = getDataSourceForSMimeEncryptedOrInlineData();
        SMimeDataSink<MimeBodyPart> sMimeDataSink = getDataSinkForSMimeDecryptedData();

        cancelableSMimeBackgroundOperation = sMimeApi.executeApiAsync(intent, dataSource, sMimeDataSink,
                new ISMimeSinkResultCallback<MimeBodyPart>() {
                    @Override
                    public void onReturn(Intent result, MimeBodyPart decryptedPart) {
                        cancelableSMimeBackgroundOperation = null;
                        currentCryptoResult = result;
                        onCryptoOperationReturned(decryptedPart);
                    }

                    @Override
                    public void onProgress(int current, int max) {
                        Timber.d("received progress status: %d / %d", current, max);
                        callbackProgress(current, max);
                    }
                });
    }

    private void callAsyncSMimeDetachedVerify(Intent intent) throws IOException, MessagingException {
        if (sMimeApi == null) {
            MimeBodyPart replacementPart = getMultipartSignedContentPartIfAvailable(currentCryptoPart.part);
            CryptoResultAnnotation annotation =
                    CryptoResultAnnotation.createErrorAnnotation(CryptoError.SMIME_SIGNED_NO_PROVIDER, replacementPart);
            addCryptoResultAnnotationToMessage(annotation);
            onCryptoFinished();
            return;
        }

        SMimeDataSource dataSource = getDataSourceForSMimeSignedData(currentCryptoPart.part);

        byte[] signatureData = MessageCryptoStructureDetector.getSignatureData(currentCryptoPart.part);
        intent.putExtra(SMimeApi.EXTRA_DETACHED_SIGNATURE, signatureData);

        sMimeApi.executeApiAsync(intent, dataSource, new ISMimeSinkResultCallback<Void>() {
            @Override
            public void onReturn(Intent result, Void dummy) {
                cancelableSMimeBackgroundOperation = null;
                currentCryptoResult = result;
                onCryptoOperationReturned(null);
            }

            @Override
            public void onProgress(int current, int max) {
                Timber.d("received progress status: %d / %d", current, max);
                callbackProgress(current, max);
            }
        });
    }

    private OpenPgpDataSink<MimeBodyPart> getDataSinkForOpenPgpDecryptedInlineData() {
        return new OpenPgpDataSink<MimeBodyPart>() {
            @Override
            public MimeBodyPart processData(InputStream is) throws IOException {
                try {
                    ByteArrayOutputStream decryptedByteOutputStream = new ByteArrayOutputStream();
                    IOUtils.copy(is, decryptedByteOutputStream);
                    TextBody body = new TextBody(new String(decryptedByteOutputStream.toByteArray()));
                    return new MimeBodyPart(body, "text/plain");
                } catch (MessagingException e) {
                    Timber.e(e, "MessagingException");
                }

                return null;
            }
        };
    }

    private OpenPgpDataSource getDataSourceForOpenPgpSignedData(final Part signedPart) throws IOException {
        return new OpenPgpDataSource() {
            @Override
            public void writeTo(OutputStream os) throws IOException {
                try {
                    Multipart multipartSignedMultipart = (Multipart) signedPart.getBody();
                    BodyPart signatureBodyPart = multipartSignedMultipart.getBodyPart(0);
                    Timber.d("signed data type: %s", signatureBodyPart.getMimeType());
                    signatureBodyPart.writeTo(os);
                } catch (MessagingException e) {
                    Timber.e(e, "Exception while writing message to crypto provider");
                }
            }
        };
    }

    private OpenPgpDataSource getDataSourceForOpenPgpEncryptedOrInlineData() throws IOException {
        return new OpenPgpApi.OpenPgpDataSource() {
            @Override
            public Long getSizeForProgress() {
                Part part = currentCryptoPart.part;
                CryptoPartType cryptoPartType = currentCryptoPart.type;
                Body body;
                if (cryptoPartType == CryptoPartType.PGP_ENCRYPTED) {
                    Multipart multipartEncryptedMultipart = (Multipart) part.getBody();
                    BodyPart encryptionPayloadPart = multipartEncryptedMultipart.getBodyPart(1);
                    body = encryptionPayloadPart.getBody();
                } else if (cryptoPartType == CryptoPartType.PGP_INLINE) {
                    body = part.getBody();
                } else {
                    throw new IllegalStateException("part to stream must be encrypted or inline!");
                }
                if (body instanceof SizeAware) {
                    return ((SizeAware) body).getSize();
                }
                return null;
            }

            @Override
            @WorkerThread
            public void writeTo(OutputStream os) throws IOException {
                try {
                    Part part = currentCryptoPart.part;
                    CryptoPartType cryptoPartType = currentCryptoPart.type;
                    if (cryptoPartType == CryptoPartType.PGP_ENCRYPTED) {
                        Multipart multipartEncryptedMultipart = (Multipart) part.getBody();
                        BodyPart encryptionPayloadPart = multipartEncryptedMultipart.getBodyPart(1);
                        Body encryptionPayloadBody = encryptionPayloadPart.getBody();
                        encryptionPayloadBody.writeTo(os);
                    } else if (cryptoPartType == CryptoPartType.PGP_INLINE) {
                        String text = MessageExtractor.getTextFromPart(part);
                        os.write(text.getBytes());
                    } else {
                        throw new IllegalStateException("part to stream must be encrypted or inline!");
                    }
                } catch (MessagingException e) {
                    Timber.e(e, "MessagingException while writing message to crypto provider");
                }
            }
        };
    }

    private OpenPgpDataSink<MimeBodyPart> getDataSinkForOpenPgpDecryptedData() throws IOException {
        return new OpenPgpDataSink<MimeBodyPart>() {
            @Override
            @WorkerThread
            public MimeBodyPart processData(InputStream is) throws IOException {
                try {
                    FileFactory fileFactory =
                            DecryptedFileProvider.getFileFactory(context);
                    return MimePartStreamParser.parse(fileFactory, is);
                } catch (MessagingException e) {
                    Timber.e(e, "Something went wrong while parsing the decrypted MIME part");
                    //TODO: pass error to main thread and display error message to user
                    return null;
                }
            }
        };
    }

    private SMimeDataSink<MimeBodyPart> getDataSinkForSMimeDecryptedInlineData() {
        return new SMimeDataSink<MimeBodyPart>() {
            @Override
            public MimeBodyPart processData(InputStream is) throws IOException {
                try {
                    ByteArrayOutputStream decryptedByteOutputStream = new ByteArrayOutputStream();
                    IOUtils.copy(is, decryptedByteOutputStream);
                    TextBody body = new TextBody(new String(decryptedByteOutputStream.toByteArray()));
                    return new MimeBodyPart(body, "text/plain");
                } catch (MessagingException e) {
                    Timber.e(e, "MessagingException");
                }

                return null;
            }
        };
    }

    private SMimeDataSource getDataSourceForSMimeSignedData(final Part signedPart) throws IOException {
        return new SMimeDataSource() {
            @Override
            public void writeTo(OutputStream os) throws IOException {
                try {
                    Multipart multipartSignedMultipart = (Multipart) signedPart.getBody();
                    BodyPart signatureBodyPart = multipartSignedMultipart.getBodyPart(0);
                    Timber.d("signed data type: %s", signatureBodyPart.getMimeType());
                    signatureBodyPart.writeTo(os);
                } catch (MessagingException e) {
                    Timber.e(e, "Exception while writing message to crypto provider");
                }
            }
        };
    }

    private SMimeDataSource getDataSourceForSMimeEncryptedOrInlineData() throws IOException {
        return new SMimeApi.SMimeDataSource() {
            @Override
            public Long getSizeForProgress() {
                Part part = currentCryptoPart.part;
                CryptoPartType cryptoPartType = currentCryptoPart.type;
                Body body;
                if (cryptoPartType == CryptoPartType.SMIME_ENCRYPTED) {
                    Multipart multipartEncryptedMultipart = (Multipart) part.getBody();
                    BodyPart encryptionPayloadPart = multipartEncryptedMultipart.getBodyPart(1);
                    body = encryptionPayloadPart.getBody();
                } else {
                    throw new IllegalStateException("part to stream must be encrypted or inline!");
                }
                if (body instanceof SizeAware) {
                    return ((SizeAware) body).getSize();
                }
                return null;
            }

            @Override
            @WorkerThread
            public void writeTo(OutputStream os) throws IOException {
                try {
                    Part part = currentCryptoPart.part;
                    CryptoPartType cryptoPartType = currentCryptoPart.type;
                    if (cryptoPartType == CryptoPartType.SMIME_ENCRYPTED) {
                        Multipart multipartEncryptedMultipart = (Multipart) part.getBody();
                        BodyPart encryptionPayloadPart = multipartEncryptedMultipart.getBodyPart(1);
                        Body encryptionPayloadBody = encryptionPayloadPart.getBody();
                        encryptionPayloadBody.writeTo(os);
                    } else {
                        throw new IllegalStateException("part to stream must be encrypted or inline!");
                    }
                } catch (MessagingException e) {
                    Timber.e(e, "MessagingException while writing message to crypto provider");
                }
            }
        };
    }

    private SMimeDataSink<MimeBodyPart> getDataSinkForSMimeDecryptedData() throws IOException {
        return new SMimeDataSink<MimeBodyPart>() {
            @Override
            @WorkerThread
            public MimeBodyPart processData(InputStream is) throws IOException {
                try {
                    FileFactory fileFactory =
                            DecryptedFileProvider.getFileFactory(context);
                    return MimePartStreamParser.parse(fileFactory, is);
                } catch (MessagingException e) {
                    Timber.e(e, "Something went wrong while parsing the decrypted MIME part");
                    //TODO: pass error to main thread and display error message to user
                    return null;
                }
            }
        };
    }

    private void onCryptoOperationReturned(MimeBodyPart decryptedPart) {
        if (currentCryptoResult == null) {
            Timber.e("Internal error: we should have a result here!");
            return;
        }

        try {
            handleCryptoOperationResult(decryptedPart);
        } finally {
            currentCryptoResult = null;
        }
    }

    private void handleCryptoOperationResult(MimeBodyPart outputPart) {
        switch (currentCryptoPart.providerType) {
            case OPENPGP:
                handleOpenPgpOperationResult(outputPart);
                break;
            case SMIME:
                handleSMimeOperationResult(outputPart);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    private void handleOpenPgpOperationResult(MimeBodyPart outputPart) {
        int resultCode = currentCryptoResult.getIntExtra(OpenPgpApi.RESULT_CODE, INVALID_OPENPGP_RESULT_CODE);
        Timber.d("OpenPGP API decryptVerify result code: %d", resultCode);

        switch (resultCode) {
            case INVALID_OPENPGP_RESULT_CODE: {
                Timber.e("Internal error: no result code!");
                break;
            }
            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED: {
                handleOpenPgpUserInteractionRequest();
                break;
            }
            case OpenPgpApi.RESULT_CODE_ERROR: {
                handleOpenPgpOperationError();
                break;
            }
            case OpenPgpApi.RESULT_CODE_SUCCESS: {
                handleOpenPgpOperationSuccess(outputPart);
                break;
            }
        }
    }

    private void handleOpenPgpUserInteractionRequest() {
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
        if (pendingIntent == null) {
            throw new AssertionError("Expecting PendingIntent on USER_INTERACTION_REQUIRED!");
        }

        callbackPendingIntent(pendingIntent);
    }

    private void handleOpenPgpOperationError() {
        OpenPgpError error = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
        Timber.w("OpenPGP API error: %s", error.getMessage());

        onCryptoOperationFailed(error);
    }

    private void handleOpenPgpOperationSuccess(MimeBodyPart outputPart) {
        OpenPgpDecryptionResult decryptionResult =
                currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_DECRYPTION);
        OpenPgpSignatureResult signatureResult =
                currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_SIGNATURE);
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
        PendingIntent insecureWarningPendingIntent = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_INSECURE_DETAIL_INTENT);
        boolean overrideCryptoWarning = currentCryptoResult.getBooleanExtra(
                OpenPgpApi.RESULT_OVERRIDE_CRYPTO_WARNING, false);

        CryptoResultAnnotation resultAnnotation = CryptoResultAnnotation.createOpenPgpResultAnnotation(decryptionResult,
                signatureResult, pendingIntent, insecureWarningPendingIntent, outputPart, overrideCryptoWarning);

        onCryptoOperationSuccess(resultAnnotation);
    }

    private void handleSMimeOperationResult(MimeBodyPart outputPart) {
        int resultCode = currentCryptoResult.getIntExtra(SMimeApi.RESULT_CODE, INVALID_OPENPGP_RESULT_CODE);
        Timber.d("S/MIME API decryptVerify result code: %d", resultCode);

        switch (resultCode) {
            case INVALID_SMIME_RESULT_CODE: {
                Timber.e("Internal error: no result code!");
                break;
            }
            case SMimeApi.RESULT_CODE_USER_INTERACTION_REQUIRED: {
                handleSMimeUserInteractionRequest();
                break;
            }
            case SMimeApi.RESULT_CODE_ERROR: {
                handleSMimeOperationError();
                break;
            }
            case SMimeApi.RESULT_CODE_SUCCESS: {
                handleSMimeOperationSuccess(outputPart);
                break;
            }
        }
    }

    private void handleSMimeUserInteractionRequest() {
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_INTENT);
        if (pendingIntent == null) {
            throw new AssertionError("Expecting PendingIntent on USER_INTERACTION_REQUIRED!");
        }

        callbackPendingIntent(pendingIntent);
    }

    private void handleSMimeOperationError() {
        SMimeError error = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_ERROR);
        Timber.w("S/MIME API error: %s", error.getMessage());

        onCryptoOperationFailed(error);
    }

    private void handleSMimeOperationSuccess(MimeBodyPart outputPart) {
        SMimeDecryptionResult decryptionResult =
                currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_DECRYPTION);
        SMimeSignatureResult signatureResult =
                currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_SIGNATURE);
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_INTENT);
        PendingIntent insecureWarningPendingIntent = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_INSECURE_DETAIL_INTENT);
        boolean overrideCryptoWarning = currentCryptoResult.getBooleanExtra(
                SMimeApi.RESULT_OVERRIDE_CRYPTO_WARNING, false);

        CryptoResultAnnotation resultAnnotation = CryptoResultAnnotation.createSMimeResultAnnotation(decryptionResult,
                signatureResult, pendingIntent, insecureWarningPendingIntent, outputPart, overrideCryptoWarning);

        onCryptoOperationSuccess(resultAnnotation);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (isCancelled) {
            return;
        }

        if (requestCode != REQUEST_CODE_USER_INTERACTION) {
            throw new IllegalStateException("got an activity result that wasn't meant for us. this is a bug!");
        }
        if (resultCode == Activity.RESULT_OK) {
            userInteractionResultIntent = data;
            nextStep();
        } else {
            onCryptoOperationCanceled();
        }
    }

    private void onCryptoOperationSuccess(CryptoResultAnnotation resultAnnotation) {
        addCryptoResultAnnotationToMessage(resultAnnotation);
        onCryptoFinished();
    }

    private void propagateEncapsulatedSignedPart(CryptoResultAnnotation resultAnnotation, Part part) {
        Part encapsulatingPart = messageAnnotations.findKeyForAnnotationWithReplacementPart(part);
        CryptoResultAnnotation encapsulatingPartAnnotation = messageAnnotations.get(encapsulatingPart);

        if (encapsulatingPart != null && resultAnnotation.hasSignatureResult()) {
            CryptoResultAnnotation replacementAnnotation =
                    encapsulatingPartAnnotation.withEncapsulatedResult(resultAnnotation);
            messageAnnotations.put(encapsulatingPart, replacementAnnotation);
        }
    }

    private void onCryptoOperationCanceled() {
        // there are weird states that get us here when we're not actually processing any part. just skip in that case
        // see https://github.com/k9mail/k-9/issues/1878
        if (currentCryptoPart != null) {
            CryptoResultAnnotation errorPart;
            switch (currentCryptoPart.providerType) {
                case OPENPGP:
                    errorPart = CryptoResultAnnotation.createOpenPgpCanceledAnnotation();
                    addCryptoResultAnnotationToMessage(errorPart);
                    break;
                case SMIME:
                    errorPart = CryptoResultAnnotation.createSMimeCanceledAnnotation();
                    addCryptoResultAnnotationToMessage(errorPart);
                    break;

            }
        }
        onCryptoFinished();
    }

    private void onCryptoOperationFailed(OpenPgpError error) {
        CryptoResultAnnotation annotation;
        if (currentCryptoPart.type == CryptoPartType.PGP_SIGNED) {
            MimeBodyPart replacementPart = getMultipartSignedContentPartIfAvailable(currentCryptoPart.part);
            annotation = CryptoResultAnnotation.createOpenPgpSignatureErrorAnnotation(error, replacementPart);
        } else {
            annotation = CryptoResultAnnotation.createOpenPgpEncryptionErrorAnnotation(error);
        }
        addCryptoResultAnnotationToMessage(annotation);
        onCryptoFinished();
    }

    private void onCryptoOperationFailed(SMimeError error) {
        CryptoResultAnnotation annotation;
        if (currentCryptoPart.type == CryptoPartType.SMIME_SIGNED) {
            MimeBodyPart replacementPart = getMultipartSignedContentPartIfAvailable(currentCryptoPart.part);
            annotation = CryptoResultAnnotation.createSMimeSignatureErrorAnnotation(error, replacementPart);
        } else {
            annotation = CryptoResultAnnotation.createSMimeEncryptionErrorAnnotation(error);
        }
        addCryptoResultAnnotationToMessage(annotation);
        onCryptoFinished();
    }

    private void addCryptoResultAnnotationToMessage(CryptoResultAnnotation resultAnnotation) {
        Part part = currentCryptoPart.part;
        messageAnnotations.put(part, resultAnnotation);

        propagateEncapsulatedSignedPart(resultAnnotation, part);
    }

    private void onCryptoFinished() {
        boolean currentPartIsFirstInQueue = partsToProcess.peekFirst() == currentCryptoPart;
        if (!currentPartIsFirstInQueue) {
            throw new IllegalStateException(
                    "Trying to remove part from queue that is not the currently processed one!");
        }
        if (currentCryptoPart != null) {
            partsToProcess.removeFirst();
            currentCryptoPart = null;
        } else {
            Timber.e(new Throwable(), "Got to onCryptoFinished() with no part in processing!");
        }
        nextStep();
    }

    private void findPartsForNextPass() {
        switch (state) {
            case START: {
                state = State.ENCRYPTION;

                findPartsForMultipartEncryptionPass();
                return;
            }

            case ENCRYPTION: {
                state = State.SIGNATURES_AND_INLINE;

                findPartsForMultipartSignaturePass();
                findPartsForPgpInlinePass();
                return;
            }

            case SIGNATURES_AND_INLINE: {
                state = State.AUTOCRYPT;

                findPartsForAutocryptPass();
                return;
            }

            case AUTOCRYPT: {
                state = State.FINISHED;
                return;
            }

            default: {
                throw new IllegalStateException("unhandled state");
            }
        }
    }

    private void cleanupAfterProcessingFinished() {
        partsToProcess.clear();
        openPgpApi = null;
        if (openPgpServiceConnection != null) {
            openPgpServiceConnection.unbindFromService();
        }
        openPgpServiceConnection = null;
    }

    public void detachCallback() {
        synchronized (callbackLock) {
            callback = null;
        }
    }

    private void reattachCallback(Message message, MessageCryptoCallback callback) {
        if (!message.equals(currentMessage)) {
            throw new AssertionError("Callback may only be reattached for the same message!");
        }
        synchronized (callbackLock) {
            this.callback = callback;

            boolean hasCachedResult = queuedResult != null || queuedPendingIntent != null;
            if (hasCachedResult) {
                Timber.d("Returning cached result or pending intent to reattached callback");
                deliverResult();
            }
        }
    }

    private void callbackPendingIntent(PendingIntent pendingIntent) {
        synchronized (callbackLock) {
            queuedPendingIntent = pendingIntent;
            deliverResult();
        }
    }

    private void callbackReturnResult() {
        synchronized (callbackLock) {
            cleanupAfterProcessingFinished();

            queuedResult = messageAnnotations;
            messageAnnotations = null;

            deliverResult();
        }
    }

    private void callbackProgress(int current, int max) {
        synchronized (callbackLock) {
            if (callback != null) {
                callback.onCryptoHelperProgress(current, max);
            }
        }
    }

    // This method must only be called inside a synchronized(callbackLock) block!
    private void deliverResult() {
        if (isCancelled) {
            return;
        }

        if (callback == null) {
            Timber.d("Keeping crypto helper result in queue for later delivery");
            return;
        }
        if (queuedResult != null) {
            callback.onCryptoOperationsFinished(queuedResult);
        } else if (queuedPendingIntent != null) {
            callback.startPendingIntentForCryptoHelper(
                    queuedPendingIntent.getIntentSender(), REQUEST_CODE_USER_INTERACTION, null, 0, 0, 0);
            queuedPendingIntent = null;
        } else {
            throw new IllegalStateException("deliverResult() called with no result!");
        }
    }

    private static class CryptoPart {
        public final CryptoProviderType providerType;
        public final CryptoPartType type;
        public final Part part;

        CryptoPart(CryptoProviderType providerType, CryptoPartType type, Part part) {
            this.providerType = providerType;
            this.type = type;
            this.part = part;
        }
    }

    private enum CryptoPartType {
        PGP_INLINE,
        PGP_ENCRYPTED,
        PGP_SIGNED,
        PLAIN_AUTOCRYPT,
        SMIME_ENCRYPTED,
        SMIME_SIGNED
    }

    @Nullable
    private static MimeBodyPart getMultipartSignedContentPartIfAvailable(Part part) {
        MimeBodyPart replacementPart = NO_REPLACEMENT_PART;
        Body body = part.getBody();
        if (body instanceof MimeMultipart) {
            MimeMultipart multipart = ((MimeMultipart) part.getBody());
            if (multipart.getCount() >= 1) {
                replacementPart = (MimeBodyPart) multipart.getBodyPart(0);
            }
        }
        return replacementPart;
    }

    private enum State {
        START, ENCRYPTION, SIGNATURES_AND_INLINE, AUTOCRYPT, FINISHED
    }
}
