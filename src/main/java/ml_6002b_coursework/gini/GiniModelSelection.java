/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 *    BinC45ModelSelection.java
 *    Copyright (C) 1999-2012 University of Waikato, Hamilton, New Zealand
 *
 */

package ml_6002b_coursework.gini;

import weka.classifiers.trees.j48.*;
import weka.core.Attribute;
import weka.core.Instances;
import weka.core.RevisionUtils;
import weka.core.Utils;

import java.util.Enumeration;

/**
 * Class for selecting a split for a given dataset.
 */
public class GiniModelSelection
        extends ModelSelection {

    /** for serialization */
    private static final long serialVersionUID = 179170923545122001L;

    /** Minimum number of instances in interval. */
    private int m_minNoObj;

    /** Use MDL correction? */
    private boolean m_useMDLcorrection;

    /** The FULL training dataset. */
    private Instances m_allData;

    /**
     * Initializes the split selection method with the given parameters.
     *
     * @param minNoObj minimum number of instances that have to occur in
     * at least two subsets induced by split
     * @param allData FULL training dataset (necessary for selection of
     * split points).
     * @param useMDLcorrection whether to use MDL adjustement when
     * finding splits on numeric attributes
     */
    public GiniModelSelection(int minNoObj, Instances allData,
                              boolean useMDLcorrection){
        m_minNoObj = minNoObj;
        m_allData = allData;
        m_useMDLcorrection = useMDLcorrection;
    }

    /**
     * Sets reference to training data to null.
     */
    public void cleanup() {

        m_allData = null;
    }

    /**
     * Selects C4.5-type split for the given dataset.
     */
    public final ClassifierSplitModel selectModel(Instances data){

        double minResult;
        double currentResult;
        GiniSplitModel[] currentModel;
        GiniSplitModel bestModel = null;
        NoSplit noSplitModel = null;
        double averageGini = 0;
        int validModels = 0;
        boolean multiVal = true;
        Distribution checkDistribution;
        double sumOfWeights;
        int i;

        try{

            // Check if all Instances belong to one class or if not
            // enough Instances to split.
            checkDistribution = new Distribution(data);
            noSplitModel = new NoSplit(checkDistribution);
            if (Utils.sm(checkDistribution.total(),2*m_minNoObj) ||
                    Utils.eq(checkDistribution.total(),
                            checkDistribution.perClass(checkDistribution.maxClass())))
                return noSplitModel;

            // Check if all attributes are nominal and have a
            // lot of values.
            Enumeration enu = data.enumerateAttributes();
            while (enu.hasMoreElements()) {
                Attribute attribute = (Attribute) enu.nextElement();
                if ((attribute.isNumeric()) ||
                        (Utils.sm((double)attribute.numValues(),
                                (0.3*(double)m_allData.numInstances())))){
                    multiVal = false;
                    break;
                }
            }
            currentModel = new GiniSplitModel[data.numAttributes()];
            sumOfWeights = data.sumOfWeights();

            // For each attribute.
            for (i = 0; i < data.numAttributes(); i++){

                // Apart from class attribute.
                if (i != (data).classIndex()){

                    // Get models for current attribute.
                    currentModel[i] = new GiniSplitModel(i,m_minNoObj,sumOfWeights,m_useMDLcorrection);
                    currentModel[i].buildClassifier(data);

                    // Check if useful split for current attribute
                    // exists and check for enumerated attributes with
                    // a lot of values.
                    if (currentModel[i].checkModel())
                        if ((data.attribute(i).isNumeric()) ||
                                (multiVal || Utils.sm((double)data.attribute(i).numValues(),
                                        (0.3*(double)m_allData.numInstances())))){
                            averageGini = averageGini+currentModel[i].giniScore();
                            validModels++;
                        }
                }else
                    currentModel[i] = null;
            }

            // Check if any useful split was found.
            if (validModels == 0)
                return noSplitModel;
            averageGini = averageGini/(double)validModels;

            // Find "best" attribute to split on.
            minResult = 0;
            for (i=0;i<data.numAttributes();i++){
                if ((i != (data).classIndex()) &&
                        (currentModel[i].checkModel()))

                    // Use 1E-3 here to get a closer approximation to the original
                    // implementation.
                    if ((currentModel[i].giniScore() >= (averageGini-1E-3)) &&
                            Utils.gr(currentModel[i].giniScore(),minResult)){
                        bestModel = currentModel[i];
                        minResult = currentModel[i].giniScore();
                    }
            }

            // Check if useful split was found.
            if (Utils.eq(minResult,0))
                return noSplitModel;

            // Add all Instances with unknown values for the corresponding
            // attribute to the distribution for the model, so that
            // the complete distribution is stored with the model.
            bestModel.distribution().
                    addInstWithUnknown(data,bestModel.attIndex());

            // Set the split point analogue to C45 if attribute numeric.
            bestModel.setSplitPoint(m_allData);
            return bestModel;
        }catch(Exception e){
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Selects C4.5-type split for the given dataset.
     */
    public final ClassifierSplitModel selectModel(Instances train, Instances test) {

        return selectModel(train);
    }

    /**
     * Returns the revision string.
     *
     * @return		the revision
     */
    public String getRevision() {
        return RevisionUtils.extract("$Revision: 8034 $");
    }
}