package com.coinomi.core.wallet;

import com.coinomi.core.crypto.KeyCrypterPin;
import com.coinomi.core.protos.Protos;
import com.google.bitcoin.crypto.ChildNumber;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.crypto.KeyCrypterException;
import com.google.bitcoin.crypto.KeyCrypterScrypt;
import com.google.bitcoin.store.UnreadableWalletException;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkState;

/**
 * @author Giannis Dzegoutanis
 */
public class WalletProtobufSerializer {

    /**
     * Formats the given wallet (transactions and keys) to the given output stream in protocol buffer format.<p>
     *
     * Equivalent to <tt>walletToProto(wallet).writeTo(output);</tt>
     */
    public static void writeWallet(Wallet wallet, OutputStream output) throws IOException {
        Protos.Wallet walletProto = toProtobuf(wallet);
        walletProto.writeTo(output);
    }

    /**
     * Returns the given wallet formatted as text. The text format is that used by protocol buffers and although it
     * can also be parsed using {@link TextFormat#merge(CharSequence, com.google.protobuf.Message.Builder)},
     * it is designed more for debugging than storage. It is not well specified and wallets are largely binary data
     * structures anyway, consisting as they do of keys (large random numbers) and
     * {@link com.google.bitcoin.core.Transaction}s which also mostly contain keys and hashes.
     */
    public static String walletToText(Wallet wallet) {
        Protos.Wallet walletProto = toProtobuf(wallet);
        return TextFormat.printToString(walletProto);
    }

    /**
     * Converts the given wallet to the object representation of the protocol buffers. This can be modified, or
     * additional data fields set, before serialization takes place.
     */
    public static Protos.Wallet toProtobuf(Wallet wallet) {
        Protos.Wallet.Builder walletBuilder = Protos.Wallet.newBuilder();

        // Populate the wallet version.
        walletBuilder.setVersion(wallet.getVersion());

        // Set the master key
        walletBuilder.setMasterKey(getMasterKeyProto(wallet));

        // Populate the scrypt parameters.
        KeyCrypter keyCrypter = wallet.getKeyCrypter();
        if (keyCrypter == null) {
            // The wallet is unencrypted.
            walletBuilder.setEncryptionType(Protos.Wallet.EncryptionType.UNENCRYPTED);
        } else {
            // The wallet is encrypted.
            if (keyCrypter instanceof KeyCrypterScrypt) {
                KeyCrypterScrypt keyCrypterScrypt = (KeyCrypterScrypt) keyCrypter;
                walletBuilder.setEncryptionType(Protos.Wallet.EncryptionType.ENCRYPTED_SCRYPT_AES);

                // Bitcoinj format to our native protobuf
                Protos.ScryptParameters.Builder encParamBuilder = Protos.ScryptParameters.newBuilder();
                encParamBuilder.setSalt(keyCrypterScrypt.getScryptParameters().getSalt());
                encParamBuilder.setR(keyCrypterScrypt.getScryptParameters().getR());
                encParamBuilder.setP(keyCrypterScrypt.getScryptParameters().getP());
                encParamBuilder.setN(keyCrypterScrypt.getScryptParameters().getN());

                walletBuilder.setEncryptionParameters(encParamBuilder);
            }
            else if (keyCrypter instanceof KeyCrypterPin) {
                walletBuilder.setEncryptionType(Protos.Wallet.EncryptionType.ENCRYPTED_AES);

            } else {
                // Some other form of encryption has been specified that we do not know how to persist.
                throw new RuntimeException("The wallet has encryption of type '" +
                        keyCrypter.getClass().toString() + "' but this WalletProtobufSerializer " +
                        "does not know how to persist this.");
            }
        }

        // Add serialized pockets
        for (WalletPocket pocket : wallet.getPockets()) {
            walletBuilder.addPockets(WalletPocketProtobufSerializer.toProtobuf(pocket));
        }

        return walletBuilder.build();
    }

    private static Protos.Key getMasterKeyProto(Wallet wallet) {
        DeterministicKey key = wallet.getMasterKey();
        Protos.Key.Builder proto = SimpleKeyChain.serializeKey(key);
        proto.setType(Protos.Key.Type.DETERMINISTIC_KEY);
        final Protos.DeterministicKey.Builder detKey = proto.getDeterministicKeyBuilder();
        detKey.setChainCode(ByteString.copyFrom(key.getChainCode()));
        for (ChildNumber num : key.getPath()) {
            detKey.addPath(num.i());
        }
        return proto.build();
    }


    /**
     * <p>Parses a wallet from the given stream, using the provided Wallet instance to load data into. This is primarily
     * used when you want to register extensions. Data in the proto will be added into the wallet where applicable and
     * overwrite where not.</p>
     *
     * <p>A wallet can be unreadable for various reasons, such as inability to open the file, corrupt data, internally
     * inconsistent data, a wallet extension marked as mandatory that cannot be handled and so on. You should always
     * handle {@link UnreadableWalletException} and communicate failure to the user in an appropriate manner.</p>
     *
     * @throws UnreadableWalletException thrown in various error conditions (see description).
     */
    public static Wallet readWallet(InputStream input) throws UnreadableWalletException {
        try {
            Protos.Wallet walletProto = parseToProto(input);
            return readWallet(walletProto);
        } catch (IOException e) {
            throw new UnreadableWalletException("Could not parse input stream to protobuf", e);
        }
    }

    /**
     * <p>Loads wallet data from the given protocol buffer and inserts it into the given Wallet object. This is primarily
     * useful when you wish to pre-register extension objects. Note that if loading fails the provided Wallet object
     * may be in an indeterminate state and should be thrown away.</p>
     *
     * <p>A wallet can be unreadable for various reasons, such as inability to open the file, corrupt data, internally
     * inconsistent data, a wallet extension marked as mandatory that cannot be handled and so on. You should always
     * handle {@link UnreadableWalletException} and communicate failure to the user in an appropriate manner.</p>
     *
     * @throws UnreadableWalletException thrown in various error conditions (see description).
     */
    public static Wallet readWallet(Protos.Wallet walletProto) throws UnreadableWalletException {
        if (walletProto.getVersion() > 1)
            throw new UnreadableWalletException.FutureVersion();

        // Check if wallet is encrypted
        final KeyCrypter crypter = getKeyCrypter(walletProto);

        DeterministicKey masterKey =
                SimpleHDKeyChain.getDeterministicKey(walletProto.getMasterKey(), null, crypter);

        Wallet wallet = new Wallet(masterKey);

        if (walletProto.hasVersion()) {
            wallet.setVersion(walletProto.getVersion());
        }

        WalletPocketProtobufSerializer pocketSerializer = new WalletPocketProtobufSerializer();
        for (Protos.WalletPocket pocketProto : walletProto.getPocketsList()) {
            WalletPocket pocket = pocketSerializer.readWallet(pocketProto, crypter);
            wallet.addPocket(pocket);
        }

        return wallet;
    }

    private static KeyCrypter getKeyCrypter(Protos.Wallet walletProto) {
        KeyCrypter crypter;
        if (walletProto.hasEncryptionType()) {
            if (walletProto.getEncryptionType() == Protos.Wallet.EncryptionType.ENCRYPTED_AES) {
                crypter = new KeyCrypterPin();
            }
            else if (walletProto.getEncryptionType() == Protos.Wallet.EncryptionType.ENCRYPTED_SCRYPT_AES) {
                checkState(walletProto.hasEncryptionParameters(), "Encryption parameters are missing");

                Protos.ScryptParameters encryptionParameters = walletProto.getEncryptionParameters();
                org.bitcoinj.wallet.Protos.ScryptParameters.Builder bitcoinjCrypter =
                        org.bitcoinj.wallet.Protos.ScryptParameters.newBuilder();
                bitcoinjCrypter.setSalt(encryptionParameters.getSalt());
                bitcoinjCrypter.setN(encryptionParameters.getN());
                bitcoinjCrypter.setP(encryptionParameters.getP());
                bitcoinjCrypter.setR(encryptionParameters.getR());

                crypter = new KeyCrypterScrypt(bitcoinjCrypter.build());
            }
            else if (walletProto.getEncryptionType() == Protos.Wallet.EncryptionType.UNENCRYPTED) {
                crypter = null;
            }
            else {
                throw new KeyCrypterException("Unsupported encryption: " + walletProto.getEncryptionType().toString());
            }
        }
        else {
            crypter = null;
        }

        return crypter;
    }


    /**
     * Returns the loaded protocol buffer from the given byte stream. You normally want
     * {@link Wallet#loadFromFile(java.io.File)} instead - this method is designed for low level work involving the
     * wallet file format itself.
     */
    public static Protos.Wallet parseToProto(InputStream input) throws IOException {
        return Protos.Wallet.parseFrom(input);
    }


}