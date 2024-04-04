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
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import mpicbg.models.PointMatch;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.ResidualCalculator;
import org.janelia.render.service.dao.MatchDao;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import static org.janelia.render.service.dao.RenderDao.MAX_TILE_SPEC_COUNT_FOR_QUERIES;

/**
 * APIs that pull data from both the render and match databases.
 *
 * @author Eric Trautman
 */
@Path("/")
@Api(tags = {"Point Match APIs"})
public class CombinedRenderAndMatchService {

    private final RenderDao renderDao;
    private final RenderDataService renderDataService;
    private final MatchDao matchDao;

    @SuppressWarnings("UnusedDeclaration")
    public CombinedRenderAndMatchService()
            throws UnknownHostException {
        this(RenderDao.build(), new RenderDataService(), MatchDao.build());
    }

    private CombinedRenderAndMatchService(final RenderDao renderDao,
                                          final RenderDataService renderDataService,
                                          final MatchDao matchDao) {
        this.renderDao = renderDao;
        this.renderDataService = renderDataService;
        this.matchDao = matchDao;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/resolvedTilesWithMatchesFrom/{matchCollection}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs", "Point Match APIs"},
            value = "Get raw tile and transform specs for specified group or bounding box " +
                    "along with the point matches for those tiles")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "too many (> " + MAX_TILE_SPEC_COUNT_FOR_QUERIES + ") matching tiles found"),
            @ApiResponse(code = 404, message = "no tile specs found"),
    })
    public Response getResolvedTilesWithMatches(@PathParam("owner") final String owner,
                                                @PathParam("project") final String project,
                                                @PathParam("stack") final String stack,
                                                @PathParam("matchCollection") final String matchCollection,
                                                @QueryParam("minZ") final Double minZ,
                                                @QueryParam("maxZ") final Double maxZ,
                                                @QueryParam("groupId") final String groupId,
                                                @QueryParam("minX") final Double minX,
                                                @QueryParam("maxX") final Double maxX,
                                                @QueryParam("minY") final Double minY,
                                                @QueryParam("maxY") final Double maxY,
                                                @QueryParam("matchPattern") final String matchPattern) {

        LOG.info("getResolvedTilesWithMatches: entry, owner={}, project={}, stack={}, matchCollection={}, minZ={}, maxZ={}, groupId={}, minX={}, maxX={}, minY={}, maxY={}, matchPattern={}",
                 owner, project, stack, matchCollection, minZ, maxZ, groupId, minX, maxX, minY, maxY, matchPattern);

        final MatchCollectionId collectionId = MatchService.getCollectionId(owner, matchCollection);
        final ResolvedTileSpecCollection resolvedTiles = renderDataService.getResolvedTiles(owner,
                                                                                            project,
                                                                                            stack,
                                                                                            minZ,
                                                                                            maxZ,
                                                                                            groupId,
                                                                                            minX,
                                                                                            maxX,
                                                                                            minY,
                                                                                            maxY,
                                                                                            matchPattern);
        final StreamingOutput responseOutput =
                output -> matchDao.writeMatchesAndTileSpecs(collectionId, resolvedTiles, output);

        return MatchService.streamResponse(responseOutput);
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

            final List<TileBounds> allTileBounds = renderDao.getTileBoundsForZ(stackId, z, null);

            final Set<String> distinctSectionIds = new HashSet<>(allTileBounds.size());
            final Map<String, TileBounds> tileIdToBoundsMap = new HashMap<>(allTileBounds.size());

            allTileBounds.forEach(tb -> {
                final String sectionId = tb.getSectionId();
                if (sectionId != null) {
                    distinctSectionIds.add(sectionId);
                    tileIdToBoundsMap.put(tb.getTileId(), tb);
                }
            });

            final Set<String> layerTileIds = tileIdToBoundsMap.keySet();
            final TileIdsWithMatches tileIdsWithMatches = new TileIdsWithMatches();

            final int numberOfSectionIds = distinctSectionIds.size();
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
                if (! clusterBoundsList.isEmpty()) {
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
            tags = { "Tile Data APIs", "Point Match APIs" },
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

            if (localMatchList.isEmpty()) {
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

            LOG.info("calculateResidualDistancesForPair: rmse is {}, distanceList.size is {} for pid {} and qId {}",
                     result.getRootMeanSquareError(), result.getDistanceListSize(), inputData.getPTileId(), inputData.getQTileId());

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return result;
    }

    private TileSpec getMatchTileSpec(final StackId matchStackId,
                                      final String tileId) {
        final TileSpec matchTileSpec = renderDao.getTileSpec(matchStackId, tileId, true);
        // TODO: handle all tile normalization options (this lazily assumes the legacy method)
        final RenderParameters matchRenderParameters =
                TileDataService.getCoreTileRenderParameters(
                        null, null, null, null,
                        null,
                        true,
                        null, null, null,
                        matchTileSpec);
        return matchRenderParameters.getTileSpecs().get(0);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CombinedRenderAndMatchService.class);
}
