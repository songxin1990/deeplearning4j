package org.deeplearning4j.nn.transferlearning;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.distribution.Distribution;
import org.deeplearning4j.nn.conf.graph.GraphVertex;
import org.deeplearning4j.nn.conf.graph.LayerVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.FeedForwardLayer;
import org.deeplearning4j.nn.conf.layers.Layer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.layers.FrozenLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.*;

/**
 * Created by susaneraly on 2/15/17.
 */
@Slf4j
public class TransferLearning {

    public static class Builder {
        private MultiLayerConfiguration origConf;
        private MultiLayerNetwork origModel;

        private MultiLayerNetwork editedModel;
        private NeuralNetConfiguration.Builder globalConfig;
        private int frozenTill = -1;
        private int popN = 0;
        private boolean prepDone = false;
        private Set<Integer> editedLayers = new HashSet<>();
        private Map<Integer, Triple<Integer, Pair<WeightInit, Distribution>, Pair<WeightInit, Distribution>>> editedLayersMap = new HashMap<>();
        private List<INDArray> editedParams = new ArrayList<>();
        private List<NeuralNetConfiguration> editedConfs = new ArrayList<>();
        private List<INDArray> appendParams = new ArrayList<>(); //these could be new arrays, and views from origParams
        private List<NeuralNetConfiguration> appendConfs = new ArrayList<>();

        private Map<Integer, InputPreProcessor> inputPreProcessors = new HashMap<>();
        private boolean pretrain = false;
        private boolean backprop = true;
        private BackpropType backpropType = BackpropType.Standard;
        private int tbpttFwdLength = 20;
        private int tbpttBackLength = 20;
        private InputType inputType;

        public Builder(MultiLayerNetwork origModel) {
            this.origModel = origModel;
            this.origConf = origModel.getLayerWiseConfigurations().clone();

            this.inputPreProcessors = origConf.getInputPreProcessors();
            this.backpropType = origConf.getBackpropType();
            this.tbpttFwdLength = origConf.getTbpttFwdLength();
            this.tbpttBackLength = origConf.getTbpttBackLength();
        }

        /**
         * NeuralNetConfiguration builder to set options (learning rate, updater etc..) for learning
         * Note that this will clear and override all other learning related settings in non frozen layers
         *
         * @param newDefaultConfBuilder
         * @return
         */
        public Builder fineTuneConfiguration(NeuralNetConfiguration.Builder newDefaultConfBuilder) {
            this.globalConfig = newDefaultConfBuilder;
            return this;
        }

        public Builder setTbpttFwdLength(int l) {
            this.tbpttFwdLength = l;
            return this;
        }

        public Builder setTbpttBackLength(int l) {
            this.tbpttBackLength = l;
            return this;
        }

        public Builder setFeatureExtractor(int layerNum) {
            this.frozenTill = layerNum;
            return this;
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         *
         * @param layerNum The index of the layer to change nOut of
         * @param nOut     Value of nOut to change to
         * @param scheme   Weight Init scheme to use for params in layernum and layernum+1
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, WeightInit scheme) {
            return nOutReplace(layerNum,nOut,scheme,scheme,null,null);
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         *
         * @param layerNum The index of the layer to change nOut of
         * @param nOut     Value of nOut to change to
         * @param dist     Distribution to use in conjunction with weight init DISTRIBUTION for params in layernum and layernum+1
         * @see org.deeplearning4j.nn.weights.WeightInit DISTRIBUTION
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, Distribution dist) {
            return nOutReplace(layerNum,nOut,WeightInit.DISTRIBUTION,WeightInit.DISTRIBUTION,dist,dist);
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         * Can specify different weight init schemes for the specified layer and the layer that follows it.
         *
         * @param layerNum   The index of the layer to change nOut of
         * @param nOut       Value of nOut to change to
         * @param scheme     Weight Init scheme to use for params in the layerNum
         * @param schemeNext Weight Init scheme to use for params in the layerNum+1
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, WeightInit scheme, WeightInit schemeNext) {
            return nOutReplace(layerNum,nOut,scheme,schemeNext,null,null);
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         * Can specify different weight init schemes for the specified layer and the layer that follows it.
         *
         * @param layerNum   The index of the layer to change nOut of
         * @param nOut       Value of nOut to change to
         * @param dist       Distribution to use for params in the layerNum
         * @param distNext   Distribution to use for parmas in layerNum+1
         * @see org.deeplearning4j.nn.weights.WeightInit DISTRIBUTION
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, Distribution dist, Distribution distNext) {
            return nOutReplace(layerNum,nOut,WeightInit.DISTRIBUTION,WeightInit.DISTRIBUTION,dist,distNext);
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         * Can specify different weight init schemes for the specified layer and the layer that follows it.
         *
         * @param layerNum   The index of the layer to change nOut of
         * @param nOut       Value of nOut to change to
         * @param scheme     Weight init scheme to use for params in layerNum
         * @param distNext   Distribution to use for parmas in layerNum+1
         * @see org.deeplearning4j.nn.weights.WeightInit DISTRIBUTION
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, WeightInit scheme, Distribution distNext) {
            return nOutReplace(layerNum,nOut,scheme,WeightInit.DISTRIBUTION,null,distNext);
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         * Can specify different weight init schemes for the specified layer and the layer that follows it.
         *
         * @param layerNum   The index of the layer to change nOut of
         * @param nOut       Value of nOut to change to
         * @param dist       Distribution to use for parmas in layerNum
         * @param schemeNext Weight init scheme to use for params in layerNum+1
         * @see org.deeplearning4j.nn.weights.WeightInit DISTRIBUTION
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, Distribution dist, WeightInit schemeNext) {
            return nOutReplace(layerNum,nOut,WeightInit.DISTRIBUTION,schemeNext,dist,null);
        }

        private Builder nOutReplace(int layerNum, int nOut, WeightInit scheme, WeightInit schemeNext, Distribution dist, Distribution distNext) {
            editedLayers.add(layerNum);
            ImmutableTriple<Integer,Pair<WeightInit,Distribution>,Pair<WeightInit,Distribution>> t = new ImmutableTriple(nOut,new ImmutablePair<>(scheme,dist),new ImmutablePair<>(schemeNext,distNext));
            editedLayersMap.put(layerNum,t);
            return this;
        }

        /**
         * Helper method to remove the outputLayer of the net.
         * Only one of the two - removeOutputLayer() or removeLayersFromOutput(layerNum) - can be specified
         * When layers are popped at the very least an output layer should be added with .addLayer(...)
         *
         * @return
         */
        public Builder removeOutputLayer() {
            popN = 1;
            return this;
        }

        /**
         * Pop last "n" layers of the net
         *
         * @param layerNum number of layers to pop, 1 will pop output layer only and so on...
         * @return
         */
        public Builder removeLayersFromOutput(int layerNum) {
            if (popN == 0) {
                popN = layerNum;
            } else {
                throw new IllegalArgumentException("Pop from can only be called once");
            }
            return this;
        }

        /**
         * Add layers to the net
         * Required if layers are popped. Can be called multiple times and layers will be added in the order with which they were called.
         * At the very least an outputLayer must be added (output layer should be added last - as per the note on order)
         * Learning configs like updaters, learning rate etc specified per layer, here will be honored
         *
         * @param layer layer conf to add
         * @return
         */
        public Builder addLayer(Layer layer) {

            if (!prepDone) {
                doPrep();
            }
            // Use the fineTune NeuralNetConfigurationBuilder and the layerConf to get the NeuralNetConfig
            //instantiate dummy layer to get the params
            NeuralNetConfiguration layerConf = globalConfig.clone().layer(layer).build();
            Layer layerImpl = layerConf.getLayer();
            int numParams = layerImpl.initializer().numParams(layerConf);
            INDArray params;
            if (numParams > 0) {
                params = Nd4j.create(1, numParams);
                org.deeplearning4j.nn.api.Layer someLayer = layerImpl.instantiate(layerConf, null, 0, params, true);
                appendParams.add(someLayer.params());
                appendConfs.add(someLayer.conf());
            }
            else {
                appendConfs.add(layerConf);

            }
            return this;
        }

        /**
         * Specify the preprocessor for the added layers
         * for cases where they cannot be inferred automatically.
         * @param index of the layer
         * @param processor to be used on the data
         * @return
         */
        public Builder setInputPreProcessor(int layer, InputPreProcessor processor) {
            inputPreProcessors.put(layer,processor);
            return this;
        }

        /**
         * Returns a model with the fine tune configuration and specified architecture changes.
         * .init() need not be called. Can be directly fit.
         *
         * @return
         */
        public MultiLayerNetwork build() {

            if (!prepDone) {
                doPrep();
            }

            editedModel = new MultiLayerNetwork(constructConf(), constructParams());
            if (frozenTill != -1) {
                org.deeplearning4j.nn.api.Layer[] layers = editedModel.getLayers();
                for (int i = frozenTill; i >= 0; i--) {
                    //unchecked?
                    layers[i] = new FrozenLayer(layers[i]);
                }
                editedModel.setLayers(layers);
            }
            return editedModel;
        }

        private void doPrep() {

            if (globalConfig == null) {
                throw new IllegalArgumentException("FineTrain config must be set with .fineTuneConfiguration");
            }

            //first set finetune configs on all layers in model
            fineTuneConfigurationBuild();

            //editParams gets original model params
            for (int i = 0; i < origModel.getnLayers(); i++) {
                if (origModel.getLayer(i).numParams() > 0) {
                    //dup only if params are there
                    editedParams.add(origModel.getLayer(i).params().dup());
                }
                else {
                    editedParams.add(origModel.getLayer(i).params());
                }
            }
            //apply changes to nout/nin if any in sorted order and save to editedParams
            if (!editedLayers.isEmpty()) {
                Integer[] editedLayersSorted = editedLayers.toArray(new Integer[editedLayers.size()]);
                Arrays.sort(editedLayersSorted);
                for (int i = 0; i < editedLayersSorted.length; i++) {
                    int layerNum = editedLayersSorted[i];
                    nOutReplaceBuild(layerNum, editedLayersMap.get(layerNum).getLeft(), editedLayersMap.get(layerNum).getMiddle(), editedLayersMap.get(layerNum).getRight());
                }
            }

            //finally pop layers specified
            int i = 0;
            while (i < popN) {
                Integer layerNum = origModel.getnLayers() - i;
                if (inputPreProcessors.containsKey(layerNum)) {
                    inputPreProcessors.remove(layerNum);
                }
                editedConfs.remove(editedConfs.size() - 1);
                editedParams.remove(editedParams.size() - 1);
                i++;
            }
            prepDone = true;

        }


        private void fineTuneConfigurationBuild() {

            for (int i = 0; i < origConf.getConfs().size(); i++) {
                NeuralNetConfiguration layerConf = origConf.getConf(i);
                Layer layerConfImpl = layerConf.getLayer().clone();
                //clear the learning related params for all layers in the origConf and set to defaults
                layerConfImpl.resetLayerDefaultConfig();
                editedConfs.add(globalConfig.clone().layer(layerConfImpl).build());
            }
        }

        private void nOutReplaceBuild(int layerNum, int nOut, Pair<WeightInit,Distribution> schemedist, Pair<WeightInit,Distribution> schemedistNext) {

            NeuralNetConfiguration layerConf = editedConfs.get(layerNum);
            Layer layerImpl = layerConf.getLayer(); //not a clone need to modify nOut in place
            layerImpl.setWeightInit(schemedist.getLeft());
            layerImpl.setDist(schemedist.getRight());
            FeedForwardLayer layerImplF = (FeedForwardLayer) layerImpl;
            layerImplF.setNOut(nOut);
            int numParams = layerImpl.initializer().numParams(layerConf);
            INDArray params = Nd4j.create(1, numParams);
            org.deeplearning4j.nn.api.Layer someLayer = layerImpl.instantiate(layerConf, null, 0, params, true);
            editedParams.set(layerNum, someLayer.params());

            if (layerNum + 1 < editedConfs.size()) {
                layerConf = editedConfs.get(layerNum + 1);
                layerImpl = layerConf.getLayer(); //modify in place
                layerImpl.setWeightInit(schemedistNext.getLeft());
                layerImpl.setDist(schemedistNext.getRight());
                layerImplF = (FeedForwardLayer) layerImpl;
                layerImplF.setNIn(nOut);
                numParams = layerImpl.initializer().numParams(layerConf);
                if (numParams > 0) {
                    params = Nd4j.create(1, numParams);
                    someLayer = layerImpl.instantiate(layerConf, null, 0, params, true);
                    editedParams.set(layerNum + 1, someLayer.params());
                }
            }

        }

        private INDArray constructParams() {
            //some params will be null for subsampling etc
            INDArray keepView =null;
            for (INDArray aParam: editedParams) {
                if (aParam != null) {
                    if (keepView == null) {
                        keepView = aParam;
                    }
                    else {
                        keepView = Nd4j.hstack(keepView,aParam);
                    }
                }
            }
            if (!appendParams.isEmpty()) {
                INDArray appendView = Nd4j.hstack(appendParams);
                return Nd4j.hstack(keepView, appendView);
            } else {
                return keepView;
            }
        }

        private MultiLayerConfiguration constructConf() {
            //use the editedConfs list to make a new config
            List<NeuralNetConfiguration> allConfs = new ArrayList<>();
            allConfs.addAll(editedConfs);
            allConfs.addAll(appendConfs);
            return new MultiLayerConfiguration.Builder().backprop(backprop).inputPreProcessors(inputPreProcessors).
                    pretrain(pretrain).backpropType(backpropType).tBPTTForwardLength(tbpttFwdLength)
                    .tBPTTBackwardLength(tbpttBackLength)
                    .setInputType(this.inputType)
                    .confs(allConfs).build();
        }
    }

    public static class GraphBuilder {

        private ComputationGraph origGraph;
        private ComputationGraphConfiguration origConfig;

        private NeuralNetConfiguration.Builder globalConfig;
        private ComputationGraphConfiguration.GraphBuilder editedConfigBuilder;

        private String frozenOutputAt;
        private boolean hasFrozen = false;
        private Set<String> editedVertices = new HashSet<>();

        public GraphBuilder(ComputationGraph origGraph) {
            this.origGraph = origGraph;
            this.origConfig = origGraph.getConfiguration().clone();

        }

        public GraphBuilder fineTuneConfiguration(NeuralNetConfiguration.Builder newDefaultConfBuilder) {
            this.globalConfig = newDefaultConfBuilder;
            this.editedConfigBuilder = new ComputationGraphConfiguration.GraphBuilder(origConfig,globalConfig,true);
            return this;
        }

        public GraphBuilder setTbpttFwdLength(int l) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.setTbpttFwdLength(l);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public GraphBuilder setTbpttBackLength(int l) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.setTbpttBackLength(l);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        //FIXME: Make this more flexible to take a string.. that specifies unique path(s) from input(s) to vertex(vertices)
        public GraphBuilder setFeatureExtractor(String layerName) {
            this.hasFrozen = true;
            this.frozenOutputAt = layerName;
            return this;
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, WeightInit scheme) {
            return nOutReplace(layerName, nOut, scheme, scheme, null, null);
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, Distribution dist) {
            return nOutReplace(layerName, nOut, WeightInit.DISTRIBUTION, WeightInit.DISTRIBUTION, dist, dist);
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, Distribution dist, Distribution distNext) {
            return nOutReplace(layerName, nOut, WeightInit.DISTRIBUTION, WeightInit.DISTRIBUTION, dist, distNext);
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, WeightInit scheme, Distribution dist) {
            return nOutReplace(layerName, nOut, scheme, WeightInit.DISTRIBUTION, null, dist);
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, Distribution dist, WeightInit scheme) {
            return nOutReplace(layerName, nOut, WeightInit.DISTRIBUTION, scheme, dist, null);
        }


        public GraphBuilder nOutReplace(String layerName, int nOut, WeightInit scheme, WeightInit schemeNext) {
            return nOutReplace(layerName, nOut, scheme, schemeNext, null, null);
        }

        private GraphBuilder nOutReplace(String layerName, int nOut, WeightInit scheme, WeightInit schemeNext, Distribution dist, Distribution distNext) {

            if (origGraph.getVertex(layerName).hasLayer()) {

                NeuralNetConfiguration layerConf = origGraph.getLayer(layerName).conf();
                Layer layerImpl = layerConf.getLayer().clone();
                layerImpl.resetLayerDefaultConfig();

                layerImpl.setWeightInit(scheme);
                layerImpl.setDist(dist);
                FeedForwardLayer layerImplF = (FeedForwardLayer) layerImpl;
                layerImplF.setNOut(nOut);

                editedConfigBuilder.removeVertex(layerName,false);
                LayerVertex lv = (LayerVertex) origConfig.getVertices().get(layerName);
                String [] lvInputs = origConfig.getVertexInputs().get(layerName).toArray(new String[0]);
                editedConfigBuilder.addLayer(layerName,layerImpl,lv.getPreProcessor(),lvInputs);
                editedVertices.add(layerName);

                //collect other vertices that have this vertex as inputs
                List<String> fanoutVertices  = new ArrayList<>();
                for (Map.Entry<String,List<String>> entry: origConfig.getVertexInputs().entrySet()) {
                    String currentVertex = entry.getKey();
                    if (!currentVertex.equals(layerName)) {
                        if (entry.getValue().contains(layerName)) {
                            fanoutVertices.add(currentVertex);
                        }
                    }
                }

                //change nIn of fanout
                for (String fanoutVertexName: fanoutVertices) {
                    if (!origGraph.getVertex(fanoutVertexName).hasLayer()) {
                        throw new UnsupportedOperationException("Cannot modify nOut of a layer vertex that feeds non-layer vertices. Use removeVertexKeepConnections followed by addVertex instead");
                    }
                    layerConf = origGraph.getLayer(fanoutVertexName).conf();
                    layerImpl = layerConf.getLayer().clone();

                    layerImpl.setWeightInit(schemeNext);
                    layerImpl.setDist(distNext);
                    layerImplF = (FeedForwardLayer) layerImpl;
                    layerImplF.setNIn(nOut);

                    editedConfigBuilder.removeVertex(fanoutVertexName,false);
                    lv = (LayerVertex) origConfig.getVertices().get(fanoutVertexName);
                    lvInputs = origConfig.getVertexInputs().get(fanoutVertexName).toArray(new String[0]);
                    editedConfigBuilder.addLayer(fanoutVertexName,layerImpl,lv.getPreProcessor(),lvInputs);
                    editedVertices.add(fanoutVertexName);
                }
            }
            else {
                throw new IllegalArgumentException("noutReplace can only be applied to layer vertices. "+layerName+" is not a layer vertex");
            }
            return this;
        }

        public GraphBuilder removeVertexKeepConnections(String outputName) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.removeVertex(outputName,false);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public GraphBuilder removeVertexAndConnections(String vertexName) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.removeVertex(vertexName,true);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public GraphBuilder addLayer(String layerName, Layer layer, String... layerInputs) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.addLayer(layerName, layer, null, layerInputs);
                editedVertices.add(layerName);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public GraphBuilder addLayer(String layerName, Layer layer, InputPreProcessor preProcessor, String... layerInputs) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.addLayer(layerName, layer, preProcessor, layerInputs);
                editedVertices.add(layerName);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public GraphBuilder addVertex(String vertexName, GraphVertex vertex, String... vertexInputs) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.addVertex(vertexName,vertex,vertexInputs);
                editedVertices.add(vertexName);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public GraphBuilder setOutputs(String... outputNames) {
            if (editedConfigBuilder != null) {
                editedConfigBuilder.setOutputs(outputNames);
            }
            else {
                throw new IllegalArgumentException("Fine tune configuration must be set first");
            }
            return this;
        }

        public ComputationGraph build() {
            ComputationGraphConfiguration newConfig = editedConfigBuilder.build();
            ComputationGraph newGraph = new ComputationGraph(newConfig);
            newGraph.init();

            int[] topologicalOrder = newGraph.topologicalSortOrder();
            org.deeplearning4j.nn.graph.vertex.GraphVertex[] vertices = newGraph.getVertices();
            if (!editedVertices.isEmpty()) {
                //set params from orig graph as necessary to new graph
                for (int i = 0; i < topologicalOrder.length; i++) {

                    if (!vertices[topologicalOrder[i]].hasLayer()) continue;

                    org.deeplearning4j.nn.api.Layer layer = vertices[topologicalOrder[i]].getLayer();
                    String layerName = vertices[topologicalOrder[i]].getVertexName();
                    int range = layer.numParams();
                    if (range <= 0) continue;    //some layers have no params
                    if (editedVertices.contains(layerName)) continue; //keep the changed params
                    layer.setParams(origGraph.getLayer(layerName).params().dup()); //copy over origGraph params
                }
            }
            else {
                newGraph.setParams(origGraph.params());
            }

            //freeze layers as necessary
            if (hasFrozen) {
                for (int i=0; i<topologicalOrder.length; i++) {
                    if (!vertices[topologicalOrder[i]].hasLayer()) continue;
                    org.deeplearning4j.nn.graph.vertex.GraphVertex gv = vertices[topologicalOrder[i]];
                    String layerName = vertices[topologicalOrder[i]].getVertexName();
                    gv.setLayerAsFrozen();
                    if (layerName.equals(frozenOutputAt)) {
                        break;
                    }
                }
                newGraph.initGradientsView();
            }
            return newGraph;
        }

    }
}