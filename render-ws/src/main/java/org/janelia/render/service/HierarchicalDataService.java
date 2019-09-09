package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.PointMatch;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasNameToPointsMap;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.alignment.transform.AffineWarpField;
import org.janelia.alignment.transform.AffineWarpFieldTransform;
import org.janelia.alignment.transform.ConsensusWarpFieldBuilder;
import org.janelia.alignment.util.ResidualCalculator;
import org.janelia.render.service.dao.MatchDao;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * APIs for accessing stack meta data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/")
@Api(tags = {"Hierarchical APIs"})
public class HierarchicalDataService {

    private final RenderDao renderDao;
    private final MatchDao matchDao;

    @SuppressWarnings("UnusedDeclaration")
    public HierarchicalDataService()
            throws UnknownHostException {
        this(RenderDao.build(), MatchDao.build());
    }

    private HierarchicalDataService(final RenderDao renderDao,
                                    final MatchDao matchDao) {
        this.renderDao = renderDao;
        this.matchDao = matchDao;
    }

    @Path("v1/owner/{owner}/project/{project}/tierData")
    @DELETE
    @ApiOperation(
            value = "Deletes stack and match data for one hierarchical tier",
            notes = "Looks at hierarchical metadata for split stacks in the specified tier project " +
                    "to delete all split stack, align stack, and match collection data for the tier.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "at least one tier split stack is READ_ONLY")
    })
    public Response deleteTierData(@PathParam("owner") final String owner,
                                   @PathParam("project") final String project) {

        LOG.info("deleteTierData: entry, owner={}, project={}", owner, project);

        String message = null;

        Response response = null;
        try {

            // Double check project is defined in case method was called internally (not via REST API).
            // A null project value would remove all tier data for an owner - probably a bad thing.
            if (project == null) {
                throw new IllegalArgumentException("project must be specified");
            }

            final List<StackMetaData> projectStackMetaDataList = renderDao.getStackMetaDataList(owner, project);
            final Set<String> existingStackNames = new HashSet<>(projectStackMetaDataList.size() * 2);

            final List<String> existingTierStackNames = new ArrayList<>(projectStackMetaDataList.size());
            final List<String> alignStackNames = new ArrayList<>(projectStackMetaDataList.size());
            final List<String> tierCollectionNames = new ArrayList<>(projectStackMetaDataList.size());

            for (final StackMetaData projectStackMetaData : projectStackMetaDataList) {

                final String projectStackName = projectStackMetaData.getStackId().getStack();
                existingStackNames.add(projectStackName);

                final HierarchicalStack hierarchicalData = projectStackMetaData.getHierarchicalData();
                if (hierarchicalData != null) {
                    validateStackIsModifiable(projectStackMetaData);
                    existingTierStackNames.add(projectStackName);
                    // not bothering to validate whether align stacks are modifiable, assume that's okay
                    alignStackNames.add(hierarchicalData.getAlignedStackId().getStack());
                    tierCollectionNames.add(hierarchicalData.getMatchCollectionId().getName());
                }
            }

            existingTierStackNames.addAll(
                    alignStackNames.stream()
                            .filter(existingStackNames::contains)
                            .collect(Collectors.toList()));

            // Remove match collections, align stacks, and then tier stacks in that order so that tier stack
            // hierarchical data is not lost if match collection or align stack removal fails.
            int numberOfRemovedCollections = 0;
            int numberOfSkippedCollections = 0;
            for (final String matchCollectionName : tierCollectionNames) {
                try {
                    matchDao.removeAllMatches(new MatchCollectionId(owner, matchCollectionName));
                    numberOfRemovedCollections++;
                } catch (final ObjectNotFoundException e) {
                    numberOfSkippedCollections++;
                }
            }

            if (numberOfSkippedCollections > 0) {
                LOG.info("skipped removal of {} non-existent collections", numberOfSkippedCollections);
            }

            // remove stacks in reverse order so align stacks are removed first
            for (int i = existingTierStackNames.size() - 1; i >= 0; i--) {
                renderDao.removeStack(new StackId(owner, project, existingTierStackNames.get(i)), true);
            }

            message = "removed " + existingTierStackNames.size() + " stacks and " + numberOfRemovedCollections +
                      " match collections for project '" + project + "' owned by '" + owner + "'";

            response = Response.ok(message, MediaType.TEXT_PLAIN_TYPE).build();

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        LOG.info("deleteTierData: exit, {}", message);

        return response;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/missingTierMatchLayers")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "List of tier stack layers (z values) without matches")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack does not have hierarchical data"),
            @ApiResponse(code = 404, message = "stack or match collection does not exist")
    })
    public List<Double> getLayersWithMissingMatches(@PathParam("owner") final String owner,
                                                    @PathParam("project") final String project,
                                                    @PathParam("stack") final String stack) {

        LOG.info("getLayersWithMissingMatches: entry, owner={}, project={}, stack={}", owner, project, stack);

        final List<Double> layersWithMissingMatches = new ArrayList<>();
        try {

            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {
                throw new ObjectNotFoundException(stackId + " does not exist");
            }

            final HierarchicalStack hierarchicalData = stackMetaData.getHierarchicalData();
            if (hierarchicalData == null) {
                throw new IllegalArgumentException(stackId + " does not have hierarchical data");
            }

            final Set<Double> layersWithMatches =
                    matchDao.getDistinctGroupIds(hierarchicalData.getMatchCollectionId())
                            .stream()
                            .map(Double::new)
                            .collect(Collectors.toSet());

            layersWithMissingMatches.addAll(
                    renderDao.getZValues(stackId)
                            .stream()
                            .filter(z -> ! layersWithMatches.contains(z))
                            .collect(Collectors.toList()));

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return layersWithMissingMatches;
    }

    @Path("v1/owner/{owner}/project/{project}/missingTierMatchLayers")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "List of tier stacks that have layers without matches")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack does not have hierarchical data"),
            @ApiResponse(code = 404, message = "stack or match collection does not exist")
    })
    public List<StackWithZValues> getStacksWithMissingMatches(@PathParam("owner") final String owner,
                                                              @PathParam("project") final String project) {

        LOG.info("getStacksWithMissingMatches: entry, owner={}, project={}", owner, project);

        final List<StackWithZValues> stacksWithMissingMatches = new ArrayList<>();
        try {

            final List<StackMetaData> projectStackMetaDataList = renderDao.getStackMetaDataList(owner, project);
            for (final StackMetaData projectStackMetaData : projectStackMetaDataList) {

                final HierarchicalStack hierarchicalData = projectStackMetaData.getHierarchicalData();

                if ((hierarchicalData != null) && hierarchicalData.hasMatchPairs()) {
                    final List<Double> layersWithMissingMatches =
                            getLayersWithMissingMatches(owner, project, projectStackMetaData.getStackId().getStack());
                    if (layersWithMissingMatches.size() > 0) {
                        stacksWithMissingMatches.add(new StackWithZValues(projectStackMetaData.getStackId(),
                                                                          layersWithMissingMatches));
                    }
                }
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        LOG.info("getStacksWithMissingMatches: exist, returning {} stacks", stacksWithMissingMatches.size());

        return stacksWithMissingMatches;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/hierarchicalData")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "The hierarchical alignment context for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public HierarchicalStack getHierarchicalData(@PathParam("owner") final String owner,
                                                 @PathParam("project") final String project,
                                                 @PathParam("stack") final String stack) {

        LOG.info("getHierarchicalData: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        HierarchicalStack hierarchicalStack = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);
            hierarchicalStack = stackMetaData.getHierarchicalData();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return hierarchicalStack;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/hierarchicalData")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Saves hierarchical alignment context for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "hierarchicalData successfully saved"),
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response saveHierarchicalData(@PathParam("owner") final String owner,
                                         @PathParam("project") final String project,
                                         @PathParam("stack") final String stack,
                                         final HierarchicalStack hierarchicalStack) {

        LOG.info("saveHierarchicalData: entry, owner={}, project={}, stack={}, hierarchicalStack={}",
                 owner, project, stack, hierarchicalStack);

        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);
            validateStackIsModifiable(stackMetaData);
            stackMetaData.setHierarchicalData(hierarchicalStack);
            renderDao.saveStackMetaData(stackMetaData);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/hierarchicalData")
    @DELETE
    @ApiOperation(
            value = "Deletes hierarchical alignment context for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response deleteHierarchicalData(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack) {
        return saveHierarchicalData(owner, project, stack, null);
    }

    @Path("v1/owner/{owner}/project/{project}/z/{z}/affineWarpFieldTransform")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Build affine warp field transform spec for layer",
            notes = "Extracts affine data from aligned stacks in the specified 'tier' project to populate " +
                    "a warp field transform and then returns the corresponding transform specification.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "invalid affine data found for one of the aligned stacks"),
            @ApiResponse(code = 404, message = "no aligned stacks exist for the specified project")
    })
    public LeafTransformSpec buildAffineWarpFieldTransform(@PathParam("owner") final String owner,
                                                           @PathParam("project") final String project,
                                                           @PathParam("z") final Double z,
                                                           @QueryParam("consensusRows") final Integer consensusRows,
                                                           @QueryParam("consensusColumns") final Integer consensusColumns,
                                                           @QueryParam("consensusBuildMethod") final ConsensusWarpFieldBuilder.BuildMethod consensusBuildMethod) {

        LOG.info("buildAffineWarpFieldTransform: entry, owner={}, project={}, z={}",
                 owner, project, z);

        LeafTransformSpec transformSpec = null;
        final int consensusRowCount = consensusRows == null ? 10 : consensusRows;
        final int consensusColumnCount = consensusRows == null ? 10 : consensusColumns;

        try {
            final List<StackMetaData> projectStacks = renderDao.getStackMetaDataList(owner, project);
            final Map<String, StackMetaData> projectStackNamesToMetadataMap = new HashMap<>(projectStacks.size() * 2);
            for (final StackMetaData stackMetaData : projectStacks) {
                projectStackNamesToMetadataMap.put(stackMetaData.getStackId().getStack(), stackMetaData);
            }

            final List<HierarchicalStack> alignedTierStacks = new ArrayList<>(projectStacks.size());

            HierarchicalStack hierarchicalStack;
            for (final StackMetaData stackMetaData : projectStacks) {
                hierarchicalStack = stackMetaData.getHierarchicalData();
                if ((hierarchicalStack != null) &&
                    (projectStackNamesToMetadataMap.containsKey(hierarchicalStack.getAlignedStackId().getStack()))) {
                    alignedTierStacks.add(hierarchicalStack);
                }
            }

            StackId alignedStackId;
            AffineModel2D relativeAlignedModel;
            final double[] affineMatrixElements = new double[6];

            TileSpec tileSpecForZ;

            if (alignedTierStacks.size() > 0) {

                LOG.info("buildAffineWarpFieldTransform: retrieving data for z {} from {} aligned stacks",
                         z, alignedTierStacks.size());

                AffineWarpField warpField = null;
                final Map<HierarchicalStack, AffineWarpField> tierStackToConsensusFieldMap = new HashMap<>();
                double[] locationOffsets = AffineWarpFieldTransform.EMPTY_OFFSETS;

                for (final HierarchicalStack tierStack : alignedTierStacks) {

                    if (warpField == null) {
                        warpField = new AffineWarpField(tierStack.getTotalTierFullScaleWidth(),
                                                        tierStack.getTotalTierFullScaleHeight(),
                                                        tierStack.getTotalTierRowCount(),
                                                        tierStack.getTotalTierColumnCount(),
                                                        AffineWarpField.getDefaultInterpolatorFactory());

                        // derive tier upper left coordinates from this split stack's position
                        final Bounds tierStackBounds = tierStack.getFullScaleBounds();
                        final double tierMinX = tierStackBounds.getMinX() -
                                                (tierStack.getTierColumn() * (tierStackBounds.getDeltaX()));
                        final double tierMinY = tierStackBounds.getMinY() -
                                               (tierStack.getTierRow() * (tierStackBounds.getDeltaY()));
                        locationOffsets = new double[] { tierMinX, tierMinY };
                    }

                    alignedStackId = tierStack.getAlignedStackId();

                    final String groupId = z.toString();

                    if (tierStack.hasSplitGroupId(groupId)) {

                        final Bounds tierStackBounds = tierStack.getFullScaleBounds();
                        final ConsensusWarpFieldBuilder builder =
                                new ConsensusWarpFieldBuilder(tierStackBounds.getDeltaX(),
                                                              tierStackBounds.getDeltaY(),
                                                              consensusRowCount,
                                                              consensusColumnCount);
                        final List<CanvasMatches> canvasMatchesList =
                                matchDao.getMatchesOutsideGroup(tierStack.getMatchCollectionId(), groupId, false);
                        final CanvasNameToPointsMap nameToPointsForGroup = new CanvasNameToPointsMap(1 / tierStack.getScale());
                        nameToPointsForGroup.addPointsForGroup(groupId, canvasMatchesList);

                        for (final String tileId : nameToPointsForGroup.getNames()) {

                            tileSpecForZ = renderDao.getTileSpec(alignedStackId, tileId, false);

                            relativeAlignedModel = getRelativeAlignedModel(tierStack,
                                                                           tileSpecForZ,
                                                                           alignedStackId);

                            builder.addConsensusSetData(relativeAlignedModel, nameToPointsForGroup.getPoints(tileId));
                        }

                        tierStackToConsensusFieldMap.put(tierStack, builder.build());

                    } else {

                        tileSpecForZ = renderDao.getTileSpec(alignedStackId, tierStack.getTileIdForZ(z), false);

                        relativeAlignedModel = getRelativeAlignedModel(tierStack,
                                                                       tileSpecForZ,
                                                                       alignedStackId);

                        relativeAlignedModel.toArray(affineMatrixElements);

                        warpField.set(tierStack.getTierRow(), tierStack.getTierColumn(), affineMatrixElements);

                    }

                }

                final String warpFieldTransformId = z + "_AFFINE_WARP_FIELD";
                if ((consensusBuildMethod == null) ||
                    ConsensusWarpFieldBuilder.BuildMethod.SIMPLE.equals(consensusBuildMethod)) {

                    transformSpec = ConsensusWarpFieldBuilder.buildSimpleWarpFieldTransformSpec(warpField,
                                                                                                tierStackToConsensusFieldMap,
                                                                                                locationOffsets,
                                                                                                warpFieldTransformId);

                } else {

                    transformSpec = ConsensusWarpFieldBuilder.buildInterpolatedWarpFieldTransformSpec(warpField,
                                                                                                      tierStackToConsensusFieldMap,
                                                                                                      locationOffsets,
                                                                                                      warpFieldTransformId);

                }

            } else {
                throw new ObjectNotFoundException("No aligned stacks exist for owner '" + owner +
                                                  "' and project '"+ project + "'.");
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return transformSpec;
    }

    @Path("v1/owner/{owner}/project/{project}/z/{z}/affineWarpFieldTransformDebug")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Debug data for layer affine warp field transform")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "invalid affine data found for one of the aligned stacks"),
            @ApiResponse(code = 404, message = "no aligned stacks exist for the specified project")
    })
    public Response getAffineWarpFieldTransformDebugData(@PathParam("owner") final String owner,
                                                         @PathParam("project") final String project,
                                                         @PathParam("z") final Double z,
                                                         @QueryParam("consensusRows") final Integer consensusRows,
                                                         @QueryParam("consensusColumns") final Integer consensusColumns,
                                                         @QueryParam("consensusBuildMethod") final ConsensusWarpFieldBuilder.BuildMethod consensusBuildMethod) {

        Response response = null;
        try {
            final LeafTransformSpec transformSpec = buildAffineWarpFieldTransform(owner,
                                                                                  project,
                                                                                  z,
                                                                                  consensusRows,
                                                                                  consensusColumns,
                                                                                  consensusBuildMethod);

            final AffineWarpFieldTransform transform = new AffineWarpFieldTransform();
            transform.init(transformSpec.getDataString());

            response = Response.ok(transform.toDebugJson(), MediaType.APPLICATION_JSON).build();

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/clusteredTileBoundsForCollection/{matchCollection}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = { "Section Data APIs", "Point Match APIs" },
            value = "Get bounds for each tile with specified z sorted by connected clusters")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<List<TileBounds>> getClusteredTileBoundsForCollection(@PathParam("owner") final String owner,
                                                                      @PathParam("project") final String project,
                                                                      @PathParam("stack") final String stack,
                                                                      @PathParam("z") final Double z,
                                                                      @PathParam("matchCollection") final String matchCollection,
                                                                      @QueryParam("matchOwner") final String matchOwner) {

        LOG.info("getClusteredTileBoundsForCollection: entry, owner={}, project={}, stack={}, z={}, matchCollection={}",
                 owner, project, stack, z, matchCollection);

        List<List<TileBounds>> result = null;

        try {

            final StackId stackId = new StackId(owner, project, stack);
            final String effectiveMatchOwner = matchOwner == null ? owner : matchOwner;
            final MatchCollectionId matchCollectionId =
                    MatchService.getCollectionId(effectiveMatchOwner, matchCollection);

            final List<TileBounds> allTileBounds = renderDao.getTileBoundsForZ(stackId, z);

            final Set<String> distinctSectionIds = new HashSet<>(allTileBounds.size());
            final Map<String, TileBounds> tileIdToBoundsMap = new HashMap<>(allTileBounds.size());

            allTileBounds.forEach(tb -> {
                final String sectionId = tb.getSectionId();
                if (sectionId != null) {
                    distinctSectionIds.add(tb.getSectionId());
                    tileIdToBoundsMap.put(tb.getTileId(), tb);
                }
            });

            final Set<String> layerTileIds = tileIdToBoundsMap.keySet();
            final TileIdsWithMatches tileIdsWithMatches = new TileIdsWithMatches();

            final int numberOfSectionIds = distinctSectionIds.size();
            if (numberOfSectionIds > 1) {
                final List<String> sortedSectionIds = distinctSectionIds.stream().sorted().collect(Collectors.toList());
                for (int i = 0; i < numberOfSectionIds; i++) {
                    final String pGroupId = sortedSectionIds.get(i);
                    tileIdsWithMatches.addMatches(matchDao.getMatchesWithinGroup(matchCollectionId,
                                                                                 pGroupId,
                                                                                 true),
                                                  layerTileIds);
                    for (int j = i + 1; j < numberOfSectionIds; j++) {
                        final String qGroupId = sortedSectionIds.get(j);
                        tileIdsWithMatches.addMatches(matchDao.getMatchesBetweenGroups(matchCollectionId,
                                                                                pGroupId,
                                                                                qGroupId,
                                                                                true),
                                                      layerTileIds);
                    }
                }
            } else {
                distinctSectionIds.forEach(
                        sectionId -> tileIdsWithMatches.addMatches(matchDao.getMatchesWithinGroup(matchCollectionId,
                                                                                             sectionId,
                                                                                             true),
                                                                   layerTileIds));
            }

            final SortedConnectedCanvasIdClusters idClusters =
                    new SortedConnectedCanvasIdClusters(tileIdsWithMatches.getCanvasMatchesList());

            LOG.info("getClusteredTileBoundsForCollection: for z {}, found {} connected tile sets with sizes {}",
                     z, idClusters.size(), idClusters.getClusterSizes());

            final List<List<TileBounds>> clusteredBoundsLists = new ArrayList<>(idClusters.size());
            idClusters.getSortedConnectedTileIdSets().forEach(tileIdSet -> {
                final List<TileBounds> clusterBoundsList = new ArrayList<>(tileIdSet.size());
                tileIdSet.forEach(tileId -> {
                    final TileBounds tileBounds = tileIdToBoundsMap.get(tileId);
                    if (tileBounds != null) {
                        clusterBoundsList.add(tileBounds);
                    }
                });
                if (clusterBoundsList.size() > 0) {
                    clusteredBoundsLists.add(clusterBoundsList);
                }
            });

            result = clusteredBoundsLists;

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return result;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/pairResidualCalculation")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Calculate alignment residual stats for a tile pair")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack, match collection, or tile not found")
    })
    public ResidualCalculator.Result calculateResidualDistancesForPair(@PathParam("owner") final String owner,
                                                                       @PathParam("project") final String project,
                                                                       @PathParam("stack") final String stack,
                                                                       @Context final UriInfo uriInfo,
                                                                       final ResidualCalculator.InputData inputData) {

        LOG.info("calculateResidualDistancesForPair: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        ResidualCalculator.Result result = null;
        try {
            final StackId matchStackId = inputData.getMatchRenderStackId();

            String pTileId = inputData.getPTileId();
            String qTileId = inputData.getQTileId();

            TileSpec pMatchTileSpec = getMatchTileSpec(matchStackId, pTileId);
            TileSpec qMatchTileSpec = getMatchTileSpec(matchStackId, qTileId);

            final CanvasMatches canvasMatches = matchDao.getMatchesBetweenObjects(inputData.getMatchCollectionId(),
                                                                                  pMatchTileSpec.getSectionId(),
                                                                                  pMatchTileSpec.getTileId(),
                                                                                  qMatchTileSpec.getSectionId(),
                                                                                  qMatchTileSpec.getTileId());
            if (! pTileId.equals(canvasMatches.getpId())) {
                final String swapTileId = pTileId;
                pTileId = qTileId;
                qTileId = swapTileId;

                final TileSpec swapMatchTileSpec = pMatchTileSpec;
                pMatchTileSpec = qMatchTileSpec;
                qMatchTileSpec = swapMatchTileSpec;

                LOG.info("calculateResidualDistancesForPair: normalized tile ordering, now pTileId is {} and qTileId is {}",
                         pTileId, qTileId);
            }

            final List<PointMatch> worldMatchList = canvasMatches.getMatches().createPointMatches();
            final List<PointMatch> localMatchList =
                    ResidualCalculator.convertMatchesToLocal(worldMatchList, pMatchTileSpec, qMatchTileSpec);

            if (localMatchList.size() == 0) {
                throw new IllegalArgumentException(inputData.getMatchCollectionId() + " has " +
                                                   worldMatchList.size() + " matches between " + pTileId + " and " +
                                                   qTileId + " but none of them are invertible");
            }

            final StackId alignedStackId = new StackId(owner, project, stack);
            final TileSpec pAlignedTileSpec = renderDao.getTileSpec(alignedStackId, pTileId, true);
            final TileSpec qAlignedTileSpec = renderDao.getTileSpec(alignedStackId, qTileId, true);

            final ResidualCalculator residualCalculator = new ResidualCalculator();
            result = residualCalculator.run(alignedStackId,
                                            inputData,
                                            localMatchList,
                                            pAlignedTileSpec,
                                            qAlignedTileSpec);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return result;
    }

    private AffineModel2D getRelativeAlignedModel(final HierarchicalStack tierStack,
                                                  final TileSpec tileSpecForZ,
                                                  final StackId alignedStackId)
            throws IllegalArgumentException {

        final AffineModel2D relativeAlignedModel;

        final TransformSpec lastTransformSpec = tileSpecForZ.getLastTransform();

        if (lastTransformSpec != null) {

            final CoordinateTransform lastTransform = lastTransformSpec.getNewInstance();

            if (lastTransform instanceof AffineModel2D) {
                relativeAlignedModel = HierarchicalStack.getFullScaleRelativeModel((AffineModel2D) lastTransform,
                                                                                   tierStack.getScale(),
                                                                                   tierStack.getFullScaleBounds());
            } else {
                throw new IllegalArgumentException(
                        "Invalid affine data for z " + tileSpecForZ.getZ() +
                        ".  Last transform for tile '" + tileSpecForZ.getTileId() + "' in " +
                        alignedStackId + " is not a 2D affine.  Tile spec is " + tileSpecForZ.toJson());
            }

        } else {
            throw new IllegalArgumentException(
                    "Invalid affine data for z " + tileSpecForZ.getZ() +
                    ".  No transforms found for tile '" + tileSpecForZ.getTileId() + "' in " +
                    alignedStackId + ".  Tile spec is " + tileSpecForZ.toJson());
        }

        return relativeAlignedModel;
    }

    private TileSpec getMatchTileSpec(final StackId matchStackId,
                                      final String tileId) {
        final TileSpec matchTileSpec = renderDao.getTileSpec(matchStackId, tileId, true);
        // TODO: handle all tile normalization options (this lazily assumes the legacy method)
        final RenderParameters matchRenderParameters =
                TileDataService.getCoreTileRenderParameters(
                        null, null, null, true, null, null, null, matchTileSpec);
        return matchRenderParameters.getTileSpecs().get(0);
    }

    private static void validateStackIsModifiable(final StackMetaData stackMetaData) {
        if (stackMetaData.isReadOnly()) {
            throw new IllegalStateException("Data for stack " + stackMetaData.getStackId().getStack() +
                                            " cannot be modified because it is " + stackMetaData.getState() + ".");
        }
    }

    private StackMetaData getStackMetaData(final StackId stackId)
            throws ObjectNotFoundException {
        return StackMetaDataService.getStackMetaData(stackId, renderDao);
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalDataService.class);
}
