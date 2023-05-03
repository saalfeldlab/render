package org.janelia.render.client.intensityadjust;

import ij.ImageJ;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.junit.Assert;
import org.junit.Test;

import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.deriveTileSpecWithFilter;
import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.showTileSpec;

/**
 * Tests the {@link AdjustBlock} class.
 *
 * @author Eric Trautman
 */
public class AdjustBlockTest {

    @Test
    public void testCorrectIntensitiesForSliceTiles() throws Exception {

        final Map<String, double[][]> tileIdToExpectedCoefficients = buildTileIdToExpectedCoefficientsMap();

        final List<TileSpec> tileSpecs =
                TileSpec.fromJsonArray(new FileReader("src/test/resources/multisem/adjust-block-test-tiles.json"));

        final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection(new ArrayList<>(),
                                                                                        tileSpecs);

        final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);

        final ArrayList<OnTheFlyIntensity> correctedList =
                AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                            ImageProcessorCache.DISABLED_CACHE,
                                                            AdjustBlock.DEFAULT_NUM_COEFFICIENTS);

        validateCorrections("default order tile list",
                            correctedList,
                            tileIdToExpectedCoefficients);
    }

    public static void main(final String[] args) {
        try {
            final List<TileSpec> tileSpecs =
                    TileSpec.fromJsonArray(new FileReader("src/test/resources/multisem/adjust-block-test-tiles.json"));

            final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection(new ArrayList<>(),
                                                                                            tileSpecs);

            final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);

            final ArrayList<OnTheFlyIntensity> correctedList =
                    AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                                ImageProcessorCache.DISABLED_CACHE,
                                                                AdjustBlock.DEFAULT_NUM_COEFFICIENTS);

            new ImageJ();
            showCorrections("default", correctedList);

            Collections.reverse(wrappedTiles);

            final ArrayList<OnTheFlyIntensity> correctedListForReverseOrderTiles =
                    AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                                ImageProcessorCache.DISABLED_CACHE,
                                                                AdjustBlock.DEFAULT_NUM_COEFFICIENTS);

            showCorrections("reverse", correctedListForReverseOrderTiles);
        } catch (final Throwable t) {
            throw new RuntimeException("caught exception", t);
        }
    }


    private static void showCorrections(final String context,
                                        final ArrayList<OnTheFlyIntensity> correctedList) {

        final double renderScale = 0.4;

        for (final OnTheFlyIntensity correctedTile : correctedList) {
            final TileSpec tileSpec = correctedTile.getMinimalTileSpecWrapper().getTileSpec();
            showTileSpec(context + " original " + tileSpec.getTileId(),
                         tileSpec,
                         renderScale,
                         ImageProcessorCache.DISABLED_CACHE);

            final double[][] coefficients = correctedTile.getCoefficients();
            final TileSpec correctedTileSpec =
                    deriveTileSpecWithFilter(tileSpec,
                                             AdjustBlock.DEFAULT_NUM_COEFFICIENTS,
                                             coefficients);
            showTileSpec(context + " corrected " + tileSpec.getTileId(),
                         correctedTileSpec,
                         renderScale,
                         ImageProcessorCache.DISABLED_CACHE);
        }
    }

    private static void validateCorrections(@SuppressWarnings("SameParameterValue") final String context,
                                            final ArrayList<OnTheFlyIntensity> correctedList,
                                            final Map<String, double[][]> tileIdToExpectedCoefficients) {

        final double allowedDelta = 0.0001;

        for (final OnTheFlyIntensity correctedTile : correctedList) {

            final String tileId = correctedTile.getMinimalTileSpecWrapper().getTileId();
            final double[][] expectedCoefficients = tileIdToExpectedCoefficients.get(tileId);
            final double[][] actualCoefficients = correctedTile.getCoefficients();

            Assert.assertEquals("coefficient array lengths differ for " + context,
                                expectedCoefficients.length, actualCoefficients.length);

            for (int i = 0; i < expectedCoefficients.length; i++) {

                final double[] expectedRow = expectedCoefficients[i];
                final double[] actualRow = actualCoefficients[i];

                Assert.assertEquals("coefficient[" + i + "] lengths differ for " + context,
                                    expectedRow.length, actualRow.length);

                for (int j = 0; j < expectedRow.length; j++) {
                    Assert.assertEquals("coefficient [" + i + "][" + j + "] values differ for " + context,
                                        expectedRow[j], actualRow[j], allowedDelta);

                }

            }

        }
    }

    private static Map<String, double[][]> buildTileIdToExpectedCoefficientsMap() {

        final Map<String, double[][]> tileIdToExpectedCoefficients = new HashMap<>();

        tileIdToExpectedCoefficients.put(
                "045_000004_016_20220401_183940.1260.0",
                new double[][]{
                        {0.9055313006403053,-0.02807292490851045},{0.9027188900435732,-0.027105132789275524},{0.8998352316997951,-0.027896644821372836},{0.8905329130596161,-0.025847234635076727},{0.8849092274919914,-0.019344738021112307},{0.8884714107869666,-0.021817294919508044},{0.894166345390529,-0.02350684484613717},{0.8935744212496664,-0.021099847142866213},{0.9079811338485335,-0.03163827331426988},{0.9052477931513085,-0.02726626107735594},{0.9048461331051393,-0.03176734518779437},{0.899466849364322,-0.029471342362801193},{0.8949452609626012,-0.027938300454886048},{0.8945150780616181,-0.026399095599478523},{0.8984774642192578,-0.024446060638879292},{0.8987347169478334,-0.02430860662417943},{0.9074001297409767,-0.03071241308687859},{0.9056417263715284,-0.02835999428091143},{0.906528548072055,-0.030266499126724154},{0.9044746879009398,-0.032246340973586654},{0.8987245972713702,-0.026171599066091004},{0.8974401273522785,-0.028250604990625955},{0.8998450526007729,-0.022906400555009216},{0.8990707859596537,-0.02247044896413037},{0.9094872407893635,-0.034153256571888266},{0.905936659217432,-0.025405637067655848},{0.9081352470243187,-0.03091405838267859},{0.9074284679061261,-0.030647959605039975},{0.9045021395271272,-0.027505340136185635},{0.9022484478317008,-0.024634009468728253},{0.9055869254215013,-0.02570855501573642},{0.9039651047747499,-0.02620011105053937},{0.9063764597549031,-0.037355130428679884},{0.9092207466727756,-0.02907914520198443},{0.908678963515164,-0.027220279346496297},{0.907841847784629,-0.028389269631503143},{0.9087863948513052,-0.028021327724341953},{0.9081764631807365,-0.02472450038439064},{0.9093805141973917,-0.024068169434919698},{0.9105049968210518,-0.02742139808499059},{0.91599493740879,-0.03925898679974449},{0.916051231683563,-0.03921318811604653},{0.9122351110770712,-0.030917228295720276},{0.9104924792949899,-0.027894347329445478},{0.9127451841632941,-0.02766789488331554},{0.9146254283411819,-0.030275372445211713},{0.9138710276604809,-0.025439128917936298},{0.9141159705569154,-0.02818841804997677},{0.9210453549603902,-0.04080275575710901},{0.9210975680999253,-0.04330354240421338},{0.9157075738630184,-0.035035238123107555},{0.9174037524439989,-0.032515074694714326},{0.9172071465526302,-0.029493252739896586},{0.9176757195555993,-0.02908258027617704},{0.9158221420766095,-0.02594586105234536},{0.9152263628426732,-0.02557924863800192},{0.9233408184267065,-0.03925275922945862},{0.9225232980887004,-0.03771359100198045},{0.9198870881677754,-0.03202678787343616},{0.9177763769171046,-0.023474343105289015},{0.919697897460771,-0.026334585142899828},{0.9192187950511442,-0.02560518890771505},{0.9183263830919877,-0.023770737643306575},{0.9187252727852103,-0.02512889511076762}
                });

        tileIdToExpectedCoefficients.put(
                "045_000004_016_20220403_125446.1266.0",
                new double[][]{
                        {1.004717038430896,0.060816245402312986},{1.0084845962736184,0.05495012278589118},{1.006301340154686,0.05903364315345137},{1.0056703844740815,0.06188112415012699},{1.0035144806412153,0.06460788704883065},{1.0074668215535465,0.057078482440179705},{1.005282201107454,0.05896977601340561},{1.0036036374542279,0.06111604881797198},{0.9980923749103764,0.07182649841764192},{1.0021228129122794,0.06556140384082501},{1.0001094416203307,0.06915596763395644},{1.0017783746830033,0.06912654791561017},{0.9978818007781814,0.0756023478148593},{1.0001850748334336,0.0696327149818449},{1.0018483024282023,0.06564993970909305},{1.0033180588456159,0.06237707490638369},{0.997999978906392,0.07263371360018561},{0.9993818922364688,0.06939040320549954},{1.0001679650981061,0.06862373061206764},{1.001379714927574,0.06813177845765699},{0.999551146499811,0.07252231630448808},{1.0009832109197758,0.06791933725483504},{1.001806083833885,0.06543449851156091},{1.0017628409703705,0.06486238374516125},{0.9999166805649818,0.06886175704148574},{0.996967079486001,0.07296377107496065},{0.9993024185464818,0.06953485480773432},{1.0030154785560217,0.06502864435929927},{1.0025933334961978,0.0668562913963504},{1.00167176857207,0.06765379593834357},{0.9994706410571453,0.06919749004507217},{1.0022005456093324,0.06458964916579261},{0.9971609984980148,0.07399560422248232},{0.997019386284195,0.07313754224138376},{0.9967351095524272,0.07321441014081585},{0.9993160676211478,0.06929891473092403},{0.9994625546001519,0.06995943743161794},{1.0005260033305234,0.0681770697302718},{0.9996500148976869,0.06902229975737464},{0.9999127037293388,0.06785791263883172},{0.9926334392947238,0.08159246897667094},{0.9937021816001615,0.07700334893697941},{0.996849922550961,0.0719990019350915},{0.9946946567244047,0.07409271542632574},{0.9957929854540707,0.07355792495596607},{0.9963532447510336,0.07252375175892097},{0.998696753763091,0.06908138861542872},{0.997397357011163,0.07043669593175021},{0.988534635946899,0.0850013483928509},{0.992649204155321,0.07799450774376825},{0.9923866898548337,0.07865398997854131},{0.9913465903124654,0.07810645834151317},{0.9950458441740053,0.07377143402028215},{0.9952984246649035,0.07315172087923388},{0.9955137645958728,0.07317404690106302},{0.9971838424855223,0.0709108500443865},{0.9890900270405059,0.08346793044496469},{0.991289009349922,0.07972231018121896},{0.9982780187976182,0.07056294961541616},{0.9982083087587381,0.06894992789733179},{0.998667108647481,0.06887513768711194},{0.9999634104758083,0.06690088136292723},{0.9963312403929389,0.07205516068050839},{0.9964158631750375,0.07228021753348012}
                });

        return tileIdToExpectedCoefficients;
    }
}
