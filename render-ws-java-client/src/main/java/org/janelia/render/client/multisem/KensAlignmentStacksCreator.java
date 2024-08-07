package org.janelia.render.client.multisem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short script that creates all stacks with the same transforms as Ken's original prototype alignment of the central
 * MFOV of wafer 53.
 */
public class KensAlignmentStacksCreator {

    public static void main(final String[] args) throws Exception {
        final long startTime = System.currentTimeMillis();

		for (final String slabName : ALL_SLABS.split(" ")) {
            final String[] argsForStack = getArgsFor(slabName);

            final KensAlignmentStacksClient.Parameters parameters = new KensAlignmentStacksClient.Parameters();
            parameters.parse(argsForStack);
            LOG.info("run with parameters={}", parameters);

            final KensAlignmentStacksClient client = new KensAlignmentStacksClient(parameters);
            client.fixStackData();
        }

        final long endTime = System.currentTimeMillis();
        LOG.info("total time: {} ms", endTime - startTime);
    }

    private static String[] getArgsFor(final String slabName) {
        final int slabNumber = getSlabNumber(slabName);
        final String project = getProjectForSlab(slabNumber);
        return new String[] {
                "--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
                "--owner", "hess_wafer_53_center7",
                "--project", project,
                "--stack", slabName + "_align_no35_hayworth_ic",
                "--targetStack", slabName + "_hayworth_alignment_replica"
        };
    }

    private static int getSlabNumber(final String slabName) {
        return Integer.parseInt(slabName.substring(1, 4));
    }

    private static String getProjectForSlab(final int slabNumber) {
        final int firstProjectSlab = 10 * (slabNumber / 10);
        final int lastProjectSlab = (firstProjectSlab == 400) ? 402 : firstProjectSlab + 9;
        return String.format("slab_%03d_to_%03d", firstProjectSlab, lastProjectSlab);
    }

    private static final String ALL_SLABS = "s001_m239 s002_m395 s003_m348 s004_m107 s005_m316 s006_m167 s007_m285 s008_m281 s009_m172 "
            + "s010_m231 s011_m151 s012_m097 s013_m333 s014_m178 s015_m278 s016_m099 s017_m330 s018_m300 s019_m073 "
            + "s020_m302 s021_m253 s022_m367 s023_m241 s024_m362 s025_m277 s026_m372 s027_m275 s028_m173 s029_m349 "
            + "s030_m016 s031_m105 s032_m133 s033_m039 s034_m081 s035_m387 s036_m252 s037_m381 s038_m139 s039_m295 "
            + "s040_m022 s041_m003 s042_m070 s043_m379 s044_m292 s045_m296 s046_m259 s047_m307 s048_m044 s049_m025 "
            + "s050_m268 s051_m287 s052_m008 s053_m188 s054_m326 s055_m089 s056_m131 s057_m055 s058_m102 s059_m355 "
            + "s060_m162 s061_m235 s062_m122 s063_m054 s064_m212 s065_m057 s066_m210 s067_m037 s068_m118 s069_m390 "
            + "s070_m104 s071_m331 s072_m150 s073_m079 s074_m265 s075_m119 s076_m033 s077_m286 s078_m279 s079_m214 "
            + "s080_m174 s081_m049 s082_m190 s083_m029 s084_m069 s085_m031 s086_m181 s087_m155 s088_m291 s089_m045 "
            + "s090_m114 s091_m246 s092_m189 s093_m228 s094_m059 s095_m221 s096_m132 s097_m149 s098_m154 s099_m233 "
            + "s100_m164 s101_m313 s102_m240 s103_m236 s104_m323 s105_m397 s106_m180 s107_m192 s108_m157 s109_m351 "
            + "s110_m141 s111_m117 s112_m213 s113_m293 s114_m094 s115_m242 s116_m341 s117_m023 s118_m092 s119_m169 "
            + "s120_m324 s121_m217 s122_m325 s123_m357 s124_m129 s125_m336 s126_m013 s127_m232 s128_m282 s129_m318 "
            + "s130_m091 s131_m043 s132_m140 s133_m305 s134_m064 s135_m078 s136_m115 s137_m388 s138_m290 s139_m111 "
            + "s140_m067 s141_m238 s142_m018 s143_m366 s144_m321 s145_m080 s146_m009 s147_m375 s148_m109 s149_m243 "
            + "s150_m280 s151_m017 s152_m145 s153_m205 s154_m124 s155_m096 s156_m198 s157_m026 s158_m177 s159_m365 "
            + "s160_m215 s161_m380 s162_m250 s163_m063 s164_m319 s165_m058 s166_m020 s167_m121 s168_m076 s169_m208 "
            + "s170_m225 s171_m260 s172_m196 s173_m166 s174_m134 s175_m194 s176_m041 s177_m146 s178_m137 s179_m036 "
            + "s180_m147 s181_m211 s182_m010 s183_m264 s184_m203 s185_m084 s186_m247 s187_m047 s188_m385 s189_m315 "
            + "s190_m294 s191_m038 s192_m086 s193_m030 s194_m182 s195_m128 s196_m120 s197_m347 s198_m306 s199_m130 "
            + "s200_m207 s201_m056 s202_m158 s203_m269 s204_m237 s205_m015 s206_m283 s207_m263 s208_m254 s209_m249 "
            + "s210_m062 s211_m350 s212_m170 s213_m386 s214_m095 s215_m222 s216_m271 s217_m392 s218_m142 s219_m199 "
            + "s220_m224 s221_m176 s222_m309 s223_m329 s224_m334 s225_m358 s226_m219 s227_m396 s228_m363 s229_m075 "
            + "s230_m126 s231_m304 s232_m314 s233_m364 s234_m289 s235_m226 s236_m195 s237_m267 s238_m266 s239_m320 "
            + "s240_m001 s241_m112 s242_m040 s243_m274 s244_m116 s245_m071 s246_m052 s247_m299 s248_m012 s249_m391 "
            + "s250_m082 s251_m108 s252_m028 s253_m100 s254_m337 s255_m103 s256_m060 s257_m369 s258_m223 s259_m230 "
            + "s260_m136 s261_m000 s262_m066 s263_m186 s264_m335 s265_m090 s266_m127 s267_m308 s268_m317 s269_m046 "
            + "s270_m024 s271_m301 s272_m053 s273_m019 s274_m165 s275_m345 s276_m204 s277_m272 s278_m193 s279_m161 "
            + "s280_m256 s281_m206 s282_m220 s283_m106 s284_m050 s285_m201 s286_m179 s287_m359 s288_m276 s289_m014 "
            + "s290_m144 s291_m262 s292_m065 s293_m400 s294_m123 s295_m175 s296_m339 s297_m048 s298_m311 s299_m034 "
            + "s300_m160 s301_m378 s302_m184 s303_m083 s304_m370 s305_m035 s306_m340 s307_m006 s308_m098 s309_m110 "
            + "s310_m368 s311_m297 s312_m171 s313_m298 s314_m338 s315_m303 s316_m068 s317_m361 s318_m389 s319_m002 "
            + "s320_m021 s321_m101 s322_m005 s323_m354 s324_m156 s325_m245 s326_m200 s327_m244 s328_m135 s329_m401 "
            + "s330_m085 s331_m251 s332_m027 s333_m163 s334_m343 s335_m011 s336_m373 s337_m394 s338_m332 s339_m032 "
            + "s340_m371 s341_m356 s342_m191 s343_m261 s344_m216 s345_m327 s346_m312 s347_m342 s348_m061 s349_m288 "
            + "s350_m352 s351_m218 s352_m234 s353_m042 s354_m093 s355_m310 s356_m197 s357_m051 s358_m074 s359_m248 "
            + "s360_m346 s361_m125 s362_m255 s363_m344 s364_m374 s365_m383 s366_m088 s367_m007 s368_m257 s369_m143 "
            + "s370_m159 s371_m087 s372_m402 s373_m258 s374_m077 s375_m284 s376_m398 s377_m202 s378_m376 s379_m229 "
            + "s380_m382 s381_m377 s382_m328 s383_m004 s384_m384 s385_m227 s386_m270 s387_m187 s388_m072 s389_m322 "
            + "s390_m273 s391_m393 s392_m168 s393_m138 s394_m360 s395_m113 s396_m153 s397_m148 s398_m183 s399_m185 "
            + "s400_m152 s401_m353 s402_m399";

    private static final Logger LOG = LoggerFactory.getLogger(KensAlignmentStacksCreator.class);
}
