import java.util.*;

public class MaxFeeTxHandler {

        private UTXOPool utxoPool;
        private Function<Transaction, List<UTXO>> getUTXOsClaimedByTx = transaction -> transaction
                        .getInputs()
                        .stream()
                        .map(in -> new UTXO(in.prevTxHash, in.outputIndex))
                        .collect(Collectors.toList());

        public MaxFeeTxHandler(UTXOPool utxoPool) {
                this.utxoPool = new UTXOPool(utxoPool);
        }

        public boolean isValidTx(Transaction tx) {
                Predicate<Transaction> utxosClaimedByTxAreInPool = transaction -> utxoPool.getAllUTXO()
                                .containsAll(getUTXOsClaimedByTx.apply(transaction));
                Predicate<Transaction> allInputsHaveValidSignature = transaction -> transaction
                                .getInputs()
                                .stream()
                                .allMatch(
                                                in -> Crypto.verifySignature(
                                                                utxoPool.getTxOutput(new UTXO(in.prevTxHash,
                                                                                in.outputIndex)).address,
                                                                transaction.getRawDataToSign(
                                                                                transaction.getInputs().indexOf(in)),
                                                                in.signature));
                Predicate<Transaction> noUTXOisClaimedMultipleTimes = transaction -> getUTXOsClaimedByTx
                                .apply(transaction)
                                .size() == getUTXOsClaimedByTx.andThen(HashSet::new).apply(transaction).size();
                Predicate<Transaction> allOutputValuesAreNonNegative = transaction -> transaction.getOutputs().stream()
                                .map(o -> o.value).noneMatch(value -> value < 0.0);
                Predicate<Transaction> totalOutputValueLessThanTotalInputValue = transaction -> transaction.getOutputs()
                                .stream().mapToDouble(o -> o.value).sum() < getUTXOsClaimedByTx
                                                .apply(transaction)
                                                .stream()
                                                .map(utxoPool::getTxOutput)
                                                .mapToDouble(o -> o.value)
                                                .sum();

                return utxosClaimedByTxAreInPool
                                .and(allInputsHaveValidSignature)
                                .and(noUTXOisClaimedMultipleTimes)
                                .and(allOutputValuesAreNonNegative)
                                .and(totalOutputValueLessThanTotalInputValue)
                                .test(tx);
        }

        public Transaction[] handleTxs(Transaction[] possibleTxs) {
                List<Transaction> validTransactions = new ArrayList<>();
                List<Transaction> possibleTransactions = new ArrayList<>(Arrays.asList(possibleTxs));
                List<Transaction> invalidTransactions = new ArrayList<>(possibleTransactions);

                UTXOPool copyOfOrigUTXOPool = new UTXOPool(utxoPool);
                while (!invalidTransactions.isEmpty()) {
                        validTransactions = new ArrayList<>();
                        utxoPool = new UTXOPool(copyOfOrigUTXOPool);
                        possibleTransactions.forEach(
                                        tx -> IntStream.range(0, tx.getOutputs().size())
                                                        .forEach(i -> utxoPool.addUTXO(new UTXO(tx.getHash(), i),
                                                                        tx.getOutput(i))));
                        possibleTransactions.sort(new FeeComparator(possibleTransactions).reversed());
                        List<Transaction> validTxInThisIter = handleTxsIter(possibleTransactions);
                        validTransactions.addAll(validTxInThisIter);
                        possibleTransactions.removeAll(validTxInThisIter);
                        invalidTransactions = new ArrayList<>(possibleTransactions);
                        possibleTransactions = new ArrayList<>(validTransactions);
                }
                return validTransactions.toArray(new Transaction[validTransactions.size()]);
        }

        private List<Transaction> handleTxsIter(List<Transaction> possibleTxs) {
                List<Transaction> validTxsInThisIter = new ArrayList<>();
                possibleTxs
                                .stream()
                                .filter(this::isValidTx)
                                .forEach(
                                                tx -> {
                                                        tx.getInputs()
                                                                        .stream()
                                                                        .map(in -> new UTXO(in.prevTxHash,
                                                                                        in.outputIndex))
                                                                        .forEach(utxoPool::removeUTXO);
                                                        validTxsInThisIter.add(tx);
                                                });
                return validTxsInThisIter;
        }

        private class FeeComparator implements Comparator<Transaction> {
                private List<Transaction> possibleTransactions;

                public FeeComparator(List<Transaction> possibleTransactions) {
                        this.possibleTransactions = possibleTransactions;
                }

                public int compare(Transaction tx1, Transaction tx2) {
                        return getTotalFeeDueTo(tx1).compareTo(getTotalFeeDueTo(tx2));
                }

                private Double getTotalFeeDueTo(Transaction tx) {
                        Double totalFee = getTxFee(tx);
                        List<UTXO> utxosOfThisTx = IntStream.range(0, tx.getOutputs().size())
                                        .mapToObj(i -> new UTXO(tx.getHash(), i))
                                        .collect(Collectors.toList());
                        List<Transaction> dependentTxs = possibleTransactions
                                        .stream()
                                        .filter(tx1 -> isValidTx(tx1))
                                        .filter(
                                                        tx1 -> getUTXOsClaimedByTx.apply(tx1).stream()
                                                                        .anyMatch(utxosOfThisTx::contains))
                                        .collect(Collectors.toList());
                        return totalFee + dependentTxs.stream().mapToDouble(tx1 -> getTotalFeeDueTo(tx1)).sum();
                }

                private Double getTxFee(Transaction transaction) {
                        return getUTXOsClaimedByTx
                                        .apply(transaction)
                                        .stream()
                                        .map(utxoPool::getTxOutput)
                                        .mapToDouble(
                                                        o -> {
                                                                if (o == null) {
                                                                        return 0.0;
                                                                }
                                                                return o.value;
                                                        })
                                        .sum()
                                        - transaction.getOutputs().stream().mapToDouble(o -> o.value).sum();
                }
        }
}