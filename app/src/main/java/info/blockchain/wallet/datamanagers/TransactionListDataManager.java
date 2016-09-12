package info.blockchain.wallet.datamanagers;

import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.Account;
import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.payload.Transaction;
import info.blockchain.wallet.payload.Tx;
import info.blockchain.wallet.rxjava.RxUtil;
import info.blockchain.wallet.util.ListUtil;
import info.blockchain.wallet.util.WebUtil;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;

import piuk.blockchain.android.di.Injector;
import rx.Observable;
import rx.schedulers.Schedulers;

public class TransactionListDataManager {

    private final String TAG_ALL = "TAG_ALL";
    private final String TAG_IMPORTED_ADDRESSES = "TAG_IMPORTED_ADDRESSES";
    @VisibleForTesting List<Tx> mTransactionList = new ArrayList<>();

    @SuppressWarnings("WeakerAccess")
    @Inject PayloadManager mPayloadManager;

    public TransactionListDataManager() {
        Injector.getInstance().getAppComponent().inject(this);
    }

    /**
     * Generates a list of transactions for a specific {@link Account} or {@link LegacyAddress}.
     * Will throw an exception if the object passed isn't either of the two types. The list will
     * be sorted by date.
     * @param object    Either a {@link Account} or a {@link LegacyAddress}
     */
    public void generateTransactionList(Object object) {
        if (object instanceof Account) {
            // V3
            mTransactionList.addAll(getV3Transactions((Account) object));
        } else if (object instanceof LegacyAddress) {
            // V2
            ListUtil.addAllIfNotNull(mTransactionList, MultiAddrFactory.getInstance().getAddressLegacyTxs(((LegacyAddress) object).getAddress()));
        } else {
            throw new IllegalArgumentException("Object must be instance of Account.class or LegacyAddress.class");
        }

        Collections.sort(mTransactionList, new TxDateComparator());
    }

    /**
     * Returns a list of {@link Tx} objects generated by {@link #getTransactionList()}
     * @return      A list of Txs sorted by date.
     */
    @NonNull
    public List<Tx> getTransactionList() {
        return mTransactionList;
    }

    /**
     * Resets the list of Transactions.
     */
    public void clearTransactionList() {
        mTransactionList.clear();
    }

    /**
     * Allows insertion of a single new {@link Tx} into the main transaction list.
     * @param transaction       A new, most likely temporary {@link Tx}
     * @return                  An updated list of Txs sorted by date
     */
    @NonNull
    public List<Tx> insertTransactionIntoListAndReturnSorted(Tx transaction) {
        mTransactionList.add(transaction);
        Collections.sort(mTransactionList, new TxDateComparator());
        return mTransactionList;
    }

    /**
     * Get total BTC balance from an {@link Account} or {@link LegacyAddress}. Will throw an exception
     * if the object passed isn't either of the two types.
     * @param object    Either a {@link Account} or a {@link LegacyAddress}
     * @return          A BTC value as a double.
     */
    public double getBtcBalance(Object object) {
        // Update Balance
        double balance = 0D;
        if (object instanceof Account) {
            //V3
            Account account = ((Account) object);
            // V3 - All
            if (account.getTags().contains(TAG_ALL)) {
                if (mPayloadManager.getPayload().isUpgraded()) {
                    // Balance = all xpubs + all legacy address balances
                    balance = ((double) MultiAddrFactory.getInstance().getXpubBalance())
                            + ((double) MultiAddrFactory.getInstance().getLegacyActiveBalance());
                } else {
                    // Balance = all legacy address balances
                    balance = ((double) MultiAddrFactory.getInstance().getLegacyActiveBalance());
                }
            } else if (account.getTags().contains(TAG_IMPORTED_ADDRESSES)) {
                balance = ((double) MultiAddrFactory.getInstance().getLegacyActiveBalance());
            } else {
                // V3 - Individual
                if (MultiAddrFactory.getInstance().getXpubAmounts().containsKey(account.getXpub())) {
                    HashMap<String, Long> xpubAmounts = MultiAddrFactory.getInstance().getXpubAmounts();
                    Long bal = (xpubAmounts.get(account.getXpub()) == null ? 0L : xpubAmounts.get(account.getXpub()));
                    balance = ((double) (bal));
                }
            }
        } else if (object instanceof LegacyAddress) {
            // V2
            LegacyAddress legacyAddress = ((LegacyAddress) object);
            balance = MultiAddrFactory.getInstance().getLegacyBalance(legacyAddress.getAddress());
        } else {
            throw new IllegalArgumentException("Object must be instance of Account.class or LegacyAddress.class");
        }

        return balance;
    }

    /**
     * Get a specific {@link Transaction} from a {@link Tx} hash.
     * @param transactionHash   The hash of the transaction to be returned
     * @return                  A Transaction object
     */
    public Observable<Transaction> getTransactionFromHash(String transactionHash) {
        return getTransactionResultString(transactionHash)
                .flatMap(this::getTransactionFromJsonString)
                .compose(RxUtil.applySchedulers());
    }

    /**
     * Update notes for a specific transaction hash and then sync the payload to the server
     * @param transactionHash   The hash of the transaction to be updated
     * @param notes             Transaction notes
     * @return                  If save was successful
     */
    public Observable<Boolean> updateTransactionNotes(String transactionHash, String notes) {
        mPayloadManager.getPayload().getNotes().put(transactionHash, notes);
        return Observable.fromCallable(() -> mPayloadManager.savePayloadToServer())
                .compose(RxUtil.applySchedulers());
    }

    private List<Tx> getV3Transactions(Account account) {
        List<Tx> transactions = new ArrayList<>();

        if (account.getTags().contains(TAG_ALL)) {
            if (mPayloadManager.getPayload().isUpgraded()) {
                transactions.addAll(getAllXpubAndLegacyTxs());
            } else {
                transactions.addAll(MultiAddrFactory.getInstance().getLegacyTxs());
            }

        } else if (account.getTags().contains(TAG_IMPORTED_ADDRESSES)) {
            // V3 - Imported Addresses
            transactions.addAll(MultiAddrFactory.getInstance().getLegacyTxs());
        } else {
            // V3 - Individual
            String xpub = account.getXpub();
            if (MultiAddrFactory.getInstance().getXpubAmounts().containsKey(xpub)) {
                ListUtil.addAllIfNotNull(transactions, MultiAddrFactory.getInstance().getXpubTxs().get(xpub));
            }
        }

        return transactions;
    }

    @VisibleForTesting
    @NonNull
    List<Tx> getAllXpubAndLegacyTxs() {
        // Remove duplicate txs
        HashMap<String, Tx> consolidatedTxsList = new HashMap<>();

        List<Tx> allXpubTransactions = MultiAddrFactory.getInstance().getAllXpubTxs();
        for (Tx tx : allXpubTransactions) {
            if (!consolidatedTxsList.containsKey(tx.getHash()))
                consolidatedTxsList.put(tx.getHash(), tx);
        }

        List<Tx> allLegacyTransactions = MultiAddrFactory.getInstance().getLegacyTxs();
        for (Tx tx : allLegacyTransactions) {
            if (!consolidatedTxsList.containsKey(tx.getHash()))
                consolidatedTxsList.put(tx.getHash(), tx);
        }

        return new ArrayList<>(consolidatedTxsList.values());
    }

    private Observable<Transaction> getTransactionFromJsonString(String json) {
        return Observable.fromCallable(() -> new Transaction(new JSONObject(json)));
    }

    private Observable<String> getTransactionResultString(String transactionHash) {
        return Observable.fromCallable(() -> WebUtil.getInstance().getURL(
                WebUtil.TRANSACTION + transactionHash + "?format=json"))
                .observeOn(Schedulers.io());
    }

    private class TxDateComparator implements Comparator<Tx> {

        TxDateComparator() {
            // Empty constructor
        }

        public int compare(Tx t1, Tx t2) {

            final int BEFORE = -1;
            final int EQUAL = 0;
            final int AFTER = 1;

            if (t2.getTS() < t1.getTS()) {
                return BEFORE;
            } else if (t2.getTS() > t1.getTS()) {
                return AFTER;
            } else {
                return EQUAL;
            }
        }
    }
}
