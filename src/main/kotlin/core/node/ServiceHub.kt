package core.node

import core.*
import core.crypto.SecureHash
import core.messaging.MessagingService
import core.node.subsystems.*
import core.node.services.*
import core.utilities.RecordingMap
import java.time.Clock

/**
 * A service hub simply vends references to the other services a node has. Some of those services may be missing or
 * mocked out. This class is useful to pass to chunks of pluggable code that might have need of many different kinds of
 * functionality and you don't want to hard-code which types in the interface.
 */
interface ServiceHub {
    val walletService: WalletService
    val keyManagementService: KeyManagementService
    val identityService: IdentityService
    val storageService: StorageService
    val networkService: MessagingService
    val networkMapCache: NetworkMapCache
    val monitoringService: MonitoringService
    val clock: Clock

    /**
     * Given a [LedgerTransaction], looks up all its dependencies in the local database, uses the identity service to map
     * the [SignedTransaction]s the DB gives back into [LedgerTransaction]s, and then runs the smart contracts for the
     * transaction. If no exception is thrown, the transaction is valid.
     */
    fun verifyTransaction(ltx: LedgerTransaction) {
        val dependencies = ltx.inputs.map {
            storageService.validatedTransactions[it.txhash] ?: throw TransactionResolutionException(it.txhash)
        }
        val ltxns = dependencies.map { it.verifyToLedgerTransaction(identityService, storageService.attachments) }
        TransactionGroup(setOf(ltx), ltxns.toSet()).verify()
    }

    /**
     * Given a list of [SignedTransaction]s, writes them to the local storage for validated transactions and then
     * sends them to the wallet for further processing.
     *
     * TODO: Need to come up with a way for preventing transactions being written other than by this method.
     * TODO: RecordingMap is test infrastructure. Refactor it away or find a way to ensure it's only used in tests.
     *
     * @param txs The transactions to record
     * @param skipRecordingMap This is used in unit testing and can be ignored most of the time.
     */
    fun recordTransactions(txs: List<SignedTransaction>, skipRecordingMap: Boolean = false) {
        val txns: Map<SecureHash, SignedTransaction> = txs.groupBy { it.id }.mapValues { it.value.first() }
        val txStorage = storageService.validatedTransactions
        if (txStorage is RecordingMap && skipRecordingMap)
            txStorage.putAllUnrecorded(txns)
        else
            txStorage.putAll(txns)
        walletService.notifyAll(txs.map { it.tx })
    }
}