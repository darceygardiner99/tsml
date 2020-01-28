package tsml.classifiers.distance_based.knn;

import evaluation.storage.ClassifierResults;
import tsml.classifiers.*;
import tsml.classifiers.distance_based.distances.Dtw;
import utilities.*;
import utilities.collections.PrunedMultimap;
import utilities.params.ParamHandler;
import utilities.params.ParamSet;
import weka.core.DistanceFunction;
import weka.core.Instance;
import weka.core.Instances;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static experiments.data.DatasetLoading.sampleGunPoint;
import static tsml.classifiers.distance_based.distances.DistanceMeasure.DISTANCE_FUNCTION_FLAG;

public class Knn extends EnhancedAbstractClassifier implements Checkpointable, MemoryWatchable, TrainTimeable {

    protected transient Instances trainData;
    public static final String K_FLAG = "k";
    public static final String EARLY_ABANDON_FLAG = "e";
    public static final String RANDOM_TIE_BREAK_FLAG = "r";
    protected int k = 1;
    protected boolean earlyAbandon = true;
    protected DistanceFunction distanceFunction = new Dtw(0);
    protected StopWatch trainTimer = new StopWatch();
    protected MemoryWatcher memoryWatcher = new MemoryWatcher();
    protected boolean randomTieBreak = false;
    protected long minCheckpointIntervalNanos = TimeUnit.NANOSECONDS.convert(1, TimeUnit.HOURS);
    protected boolean ignorePreviousCheckpoints = false;
    protected long lastCheckpointTimeStamp = 0;
    protected String checkpointDirPath;
    public static final String checkpointFileName = "checkpoint.ser";
    public static final String tempCheckpointFileName = checkpointFileName + ".tmp";

    public StopWatch getTrainTimer() {
        return trainTimer;
    }

    public Instances getTrainData() {
        return trainData;
    }

    public long getLastCheckpointTimeStamp() {
        return lastCheckpointTimeStamp;
    }

    @Override
    public boolean setSavePath(String path) {
        if(path == null) {
            return false;
        }
        checkpointDirPath = StrUtils.asDirPath(path);
        return true;
    }

    @Override
    public String getSavePath() {
        return checkpointDirPath;
    }

    public void checkpoint() throws Exception {
        trainTimer.suspend();
        memoryWatcher.suspend();
        if(isCheckpointing() && (built || lastCheckpointTimeStamp + minCheckpointIntervalNanos < System.nanoTime())) {
            final String path = checkpointDirPath + tempCheckpointFileName;
            logger.fine(() -> "saving checkpoint to: " + path);
            saveToFile(path);
            final boolean success = new File(path).renameTo(new File(checkpointDirPath + checkpointFileName));
            if(!success) {
                throw new IllegalStateException("could not rename checkpoint file");
            }
            lastCheckpointTimeStamp = System.nanoTime();
        }
        memoryWatcher.unsuspend();
        trainTimer.unsuspend();
    }

    protected void loadFromCheckpoint() throws Exception {
        trainTimer.suspend();
        memoryWatcher.suspend();
        if(!isIgnorePreviousCheckpoints() && isCheckpointing() && isRebuild()) {
            final String path = checkpointDirPath + checkpointFileName;
            logger.fine(() -> "loading from checkpoint: " + path);
            loadFromFile(path);
        }
        memoryWatcher.unsuspend();
        trainTimer.unsuspend();
    }

    @Override public boolean isIgnorePreviousCheckpoints() {
        return ignorePreviousCheckpoints;
    }

    @Override public void setIgnorePreviousCheckpoints(final boolean ignorePreviousCheckpoints) {
        this.ignorePreviousCheckpoints = ignorePreviousCheckpoints;
    }

    @Override public void setMinCheckpointIntervalNanos(final long nanos) {
        minCheckpointIntervalNanos = nanos;
    }

    @Override public long getMinCheckpointIntervalNanos() {
        return minCheckpointIntervalNanos;
    }

    @Override public MemoryWatcher getMemoryWatcher() {
        return memoryWatcher;
    }

    public boolean isRandomTieBreak() {
        return randomTieBreak;
    }

    public void setRandomTieBreak(boolean randomTieBreak) {
        this.randomTieBreak = randomTieBreak;
    }

    public Knn() {
        super(false);
    }

    public Knn(DistanceFunction df) {
        this();
        setDistanceFunction(df);
    }

    public boolean isEarlyAbandon() {
        return earlyAbandon;
    }

    public void setEarlyAbandon(final boolean earlyAbandon) {
        this.earlyAbandon = earlyAbandon;
    }

    @Override public ParamSet getParams() {
        return super.getParams()
                     .add(EARLY_ABANDON_FLAG, earlyAbandon)
                     .add(RANDOM_TIE_BREAK_FLAG, randomTieBreak)
                     .add(K_FLAG, k)
                     .add(DISTANCE_FUNCTION_FLAG, distanceFunction);
    }

    @Override public void setParams(final ParamSet params) {
        ParamHandler.setParam(params, DISTANCE_FUNCTION_FLAG, this::setDistanceFunction, DistanceFunction.class);
        ParamHandler.setParam(params, K_FLAG, this::setK, Integer.class);
        ParamHandler.setParam(params, RANDOM_TIE_BREAK_FLAG, this::setRandomTieBreak, Boolean.class);
        ParamHandler.setParam(params, EARLY_ABANDON_FLAG, this::setEarlyAbandon, Boolean.class);
    }

    @Override public void buildClassifier(final Instances trainData) throws Exception {
        loadFromCheckpoint();
        memoryWatcher.enable();
        trainTimer.enable();
        if(rebuild) {
            trainTimer.resetAndEnable();
            memoryWatcher.resetAndEnable();
            super.buildClassifier(trainData);
            rebuild = false;
            built = true;
            distanceFunction.setInstances(trainData);
            this.trainData = trainData;
            checkpoint();
        }
        trainTimer.disable();
        memoryWatcher.cleanup();
        memoryWatcher.disable();
    }

    // todo fail capabilities

    public class NeighbourSearcher implements Serializable {
        private final PrunedMultimap<Double, Instance> prunedMap;
        private final Instance instance;
        private double limit = Double.POSITIVE_INFINITY;
        private StopWatch comparisonTimer = new StopWatch();
        private StopWatch predictTimer = new StopWatch();

        public Instance getInstance() {
            return instance;
        }

        public NeighbourSearcher(Instance instance) {
            this.prunedMap =
                new PrunedMultimap<>(((Comparator<Double> & Serializable) Double::compare));
            prunedMap.setSoftLimit(k);
            this.instance = instance;
        }

        public double add(Instance neighbour) {
            final long timeStamp = System.nanoTime();
            final double distance = distanceFunction.distance(this.instance, neighbour, limit);
            add(neighbour, distance, System.nanoTime() - timeStamp);
            return distance;
        }

        public void add(Instance neighbour, double distance, long distanceMeasurementTime) {
            comparisonTimer.enable();
            prunedMap.put(distance, neighbour);
            if(earlyAbandon) {
                limit = prunedMap.lastKey();
            }
            comparisonTimer.add(distanceMeasurementTime);
            comparisonTimer.disable();
        }

        public double[] predict() {
            predictTimer.resetAndEnable();
            final PrunedMultimap<Double, Instance> nearestNeighbourMap = prunedMap;
            final double[] distribution = new double[instance.numClasses()];
            if(nearestNeighbourMap.isEmpty()) {
                distribution[rand.nextInt(distribution.length)]++;
            } else {
                for(final Double key : nearestNeighbourMap.keys()) {
                    for(final Instance nearestNeighbour : nearestNeighbourMap.get(key)) {
                        distribution[(int) nearestNeighbour.classValue()]++; // todo weight by distance
                        if(!randomTieBreak) {
                            break;
                        }
                    }
                    if(!randomTieBreak) {
                        break;
                    }
                }
                ArrayUtilities.normaliseInPlace(distribution);
            }
            predictTimer.disable();
            return distribution;
        }

        public long getTimeNanos() {
            return predictTimer.getTimeNanos() + comparisonTimer.getTimeNanos();
        }

        public double getLimit() {
            return limit;
        }
    }

    @Override
    public double[] distributionForInstance(final Instance testInstance) throws
                                                                     Exception {
        final NeighbourSearcher searcher = new NeighbourSearcher(testInstance);
        for(final Instance trainInstance : trainData) {
            searcher.add(trainInstance);
        }
        return searcher.predict();
    }

    public int getK() {
        return k;
    }

    public void setK(final int k) {
        this.k = k;
    }

    public DistanceFunction getDistanceFunction() {
        return distanceFunction;
    }

    public void setDistanceFunction(final DistanceFunction distanceFunction) {
        this.distanceFunction = distanceFunction;
    }

    public static void main(String[] args) throws Exception {
        int seed = 0;
        Instances[] data = sampleGunPoint(seed);
        Instances trainData = data[0];
        Knn classifier = new Knn(new Dtw(trainData.numAttributes() - 1));
        classifier.setSeed(0);
        ClassifierResults results = ClassifierTools.trainAndTest(data, classifier);
        System.out.println(results.writeSummaryResultsToString());
    }

}
