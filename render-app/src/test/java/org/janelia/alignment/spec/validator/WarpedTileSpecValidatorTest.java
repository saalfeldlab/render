package org.janelia.alignment.spec.validator;

import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link WarpedTileSpecValidator} class.
 *
 * @author Eric Trautman
 */
public class WarpedTileSpecValidatorTest {

    @Test
    public void testValidate() {

        final String goodTileJson =
                "{\n" +
                "    \"tileId\" : \"151217174513013101.27.0\",\n" +
                "    \"z\" : 27.0, \"width\" : 2560.0, \"height\" : 2160.0,\n" +
                "    \"transforms\" : {\n" +
                "      \"type\" : \"list\",\n" +
                "      \"specList\" : [ {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.NonLinearCoordinateTransform\",\n" +
                "        \"dataString\" : \"5 21 704.8732993311332 -5.534806990098543 9.156031962525754 609.593493921269 -12.315037212092804 6.033748343195075 -6.146854702917316 -2.3834251026801727 -13.240369417701206 -2.592248267591482 10.186277705154861 2.8649511009841646 -0.08134484318613566 3.0086034121790206 4.630071669544377 -5.056489135794557 9.141024169745165 -3.2961293648077934 -2.207036556897748 -6.084691424696807 0.8922970488355482 1.0821447201876708 1.1915641968414334 2.7415954603994246 -0.6299866955545994 2.3919440128165532 -7.420023714465529 6.687531922348452 0.6383338732644859 2.4569958115871735 0.255474973910512 -0.6799181116904955 -0.9644519200929667 0.26817741672578954 0.0785551213124902 -1.7002213256180867 0.10463086594472215 -0.19736269429571074 2.7118813076144797 -2.148443166297133 13.730338072994313 10.003759707987046 1373.0349290226845 1000.37750284507 2374894.745313172 1377074.8246285708 1368835.1297772678 4.558512091802997E9 2.3891806912798686E9 1.8877564512200985E9 2.1439374061203809E9 9.296435658070086E12 4.597900772939865E12 3.275446057068828E12 2.9580872377971133E12 3.617749880302523E12 1.9731815861490968E16 9.397571006491358E15 6.304230722262247E15 5.127919025584086E15 4.990541386080311E15 6.39820666582689E15 100.0 699.777508629714 606.7075594384752 1912184.740268692 1174378.0239293377 1320646.4277029906 4.721925950466223E9 2.6795479221869965E9 2.2532662639204607E9 2.662277986705109E9 1.1440646197748406E13 6.23163370153142E12 4.813135804729775E12 4.364646151782528E12 5.331701790670701E12 2.7618915512767036E16 1.4673556463107854E16 1.0847084516830414E16 9.07188267448321E15 8.571257879350347E15 1.0717987537604292E16 0.0 2560 2160 \"\n" +
                "      }, {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "        \"dataString\" : \"0.9922074 0.047743354 -0.054080375 1.0014949 130.0 30.0\"\n" +
                "      }, {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "        \"dataString\" : \"-1.001208388962 0.001261075342 -0.000043852343 -1.000019822789 33772.465052685169 19016.062395617591\"\n" +
                "      }, {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.ThinPlateSplineTransform\",\n" +
                "        \"dataString\" : \"ThinPlateSplineR2LogR 2 38 @v4kAzQaHZ8c/u4fEntTCKb+90GDj7IiMv0+jEZPAfndA+4UqH8Kk2kDo7VZSfrGf @QOGpAgdv+wpA0Wl5iL6EaEDlfr7c52cQQM+HRiKuw+lA4bnh+snxR0DHZW0a6hHLQOKzhptGB0xAx4d5KbrmW0DgzpHnzXLuQNF96Lm+cd9A37K+RIHRE0DVJnNyz+VaQOOs3lowcC9Az2f33Rs1skDfqx3noSWPQNFttqau11tA3iH1J3n/p0C+nxtvzWT1QOADt11DNyVAv0MMlvjoqUDf48nrbsVoQMsCQIWiW2dA3cNGy2LNLEDRM7Uf/+XfQOSlLkcVMSFAz4bEFZVmHEDb8SgeKpZmQMblFsDaE+ZA4Pz+9XyAhkC/blVCE6A+QODqxP7ygl5AyyDhFJGPQkDb0B7l+3c3QNElktJZET5A4cYGd4kJ10DLJwBwPvZJQOK/q58ikGZAyzjXl7GW5EDb/T7sZ1Z+QML7TpEAVK9A39gnHBYziUDO4r12Xc+IQN3yQuBw7XVAzpNWZLuSY0Dd8Q0E1q8tQMMirM2y+JNA39huYIfd10DDjzgBJDM4QODlBMxWzu9AzvlUDUMDDUDb/2qEkIVAQM5+N4AVOvdA4b+FZhxRDEDDjhWek+U6QOHALukmAxtAzwNkzvkVfEDg5XRvKgArQMO48BnAdG5A4MhczrT0c0DTajOdS5JvQN+epLkqdtdA017k2VKI1UDiuXeRPEefQM8ghCB9yBBA3bbUQmLeHEDTIAL155bvQN3lagTPCNtAxvlLEBZ1YEDd/fIaH3xdQMq6R2PPGgJA38yYCmevmEDHeKzWQHi8QNwK99NGYCBAypyRk9LIIUDg35F3qpOJQMeO4gI293y+iLUHBAJn4D6fU9+iOvjVvpZ96taSwli+oMzREvCQqL6z18ohq7fhPpyVBZWbed2+hQajDsAahz6rzWBAVNGLvqe48v6JnA4+ozGt13pYFz6hWZxrC+zdPpBHyJN0Dva+igro9yp5Uz41a16Vg0vtvrqLjZLT1Hk+kiHlPYLVIT6DV99fRM6sPq9F8iIFJMW+sf+Xm79oPD6k246wMvI8vnOWCJGc3wO+nr9s7KSPkT6YLgsYjazfPqYFIx0+FuE+lwSlzpt2er59Lw1RQZLCProCfhVzJqm+s7KsvbMmNz6dvEnhpvJ0Pq+lQF7EioK+seKRudZYYj62C100E9cEvp4g7Kkvu56+qJwQv4iX7r6DEOjbLRTYvoqKVnNUJE4+nJPtoIOB+L6o+WfI8fFlvpFoh3a6eLi+bX8QFMlqZb6tQRCagx6rPrBxs7CDjes+sMQB+rENkD6y/rAyjqVUvlpMs05HrSG+kHb5C4f2Dr6UJ1LaPVf1vqa7UG9mg7k+gEKbxGGCkT6sG97s4eu+vm8ZQVoMQUC+pSMKVpVrez6bb4MYYWLqPoHwQfpUISk+lTg0jcHajz6KVk50jt6evqR5ZwZ6oKA+he97b3CHcL55uAwSC9bVvoPiD+XsNKs+smA1jenAJT6TcvWy9bVYvpQW6876WuO+l5IaWpvzv76j+fe1vIutvmGfq8bhoNI+nrv5abteAb65A4dV0e6bPqVjzforX4M+pgoMS/6P1768svoKUT5Avoqjt0LBEGA+rV1w9+aKXr6nBxRWDH6SPnGdRpS9oOW+XAwN1Z2o7g==\"\n" +
                "      } ]\n" +
                "    },\n" +
                "    \"meshCellSize\" : 64.0\n" +
                "  }";

        Assert.assertTrue("good tile spec should not have failed validation", isTileSpecValid(goodTileJson));

        final String badTileJson =
                "{\n" +
                "    \"tileId\" : \"151217174513007106.27.0\",\n" +
                "    \"z\" : 27.0, \"width\" : 2560.0, \"height\" : 2160.0,\n" +
                "    \"transforms\" : {\n" +
                "      \"type\" : \"list\",\n" +
                "      \"specList\" : [ {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.NonLinearCoordinateTransform\",\n" +
                "        \"dataString\" : \"5 21 691.2114948311125 7.163845747396791 -6.624814849474052 609.7024770817226 -3.808073672047863 -0.1822316384349918 1.6908956714245895 -3.8212836822184277 4.300859304211463 -10.930934296472286 -6.020773261580057 -6.9852793340083785 2.7722489013023335 -2.250163891313635 1.6769915022988657 -3.8563273212591165 -7.1307444634896315 9.890767418016932 13.235919528741814 1.893336643096596 -3.2072630546175525 4.8903197509464915 0.11244430233588876 4.619807174649979 1.7299178102084092 1.1439216797030225 9.654118369818123 -6.333103942596119 -5.07269093205751 -0.09816132238111219 1.3823734058397885 -1.793199692495652 0.2750166894497603 -1.135523975782907 -0.7530119956276183 -1.6171079180159595 -0.4078121127966554 0.5609382134905045 -3.709584257289093 2.1390807551323254 11.211275851323007 11.594523331957987 1121.1287549551255 1159.4509268031804 1736159.6840325268 1318309.3014149456 1706343.6890349027 3.1206021801151032E9 2.0528939717061195E9 1.9550854646504486E9 2.781874761093842E9 6.10760680645622E12 3.6963437333135225E12 3.0524324080098013E12 3.198789842754736E12 4.805071036741867E12 1.2626099483755852E16 7.234479990587084E15 5.496936366091204E15 4.997334348798989E15 5.533622738130575E15 8.618487985364877E15 100.0 692.2779993332111 601.6905030471386 1758828.5589105575 1146535.931317751 1376058.3737661568 4.165985316920549E9 2.556289996576629E9 2.195439120032378E9 2.8526299826816087E9 9.835623759674057E12 5.812438203115683E12 4.576780830349617E12 4.2756009284143667E12 5.8164358141042295E12 2.33259692083136E16 1.345487615505694E16 1.0119321775756374E16 8.613975078265323E15 8.441806301390992E15 1.1838194530532674E16 0.0 2560 2160 \"\n" +
                "      }, {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "        \"dataString\" : \"1.0086069 0.05041448 -0.038706347 0.97960186 130.0 30.0\"\n" +
                "      }, {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "        \"dataString\" : \"-0.998320738028 0.010829872885 -0.004060676215 -0.997055413806 45038.025484362101 9516.938957370432\"\n" +
                "      }, {\n" +
                "        \"className\" : \"mpicbg.trakem2.transform.ThinPlateSplineTransform\",\n" +
                "        \"dataString\" : \"ThinPlateSplineR2LogR 2 3 @wDlf46b0q3dAY9uCCMEuAMAm1ae/NdgUQFGyzD3SX/U/zQckf0cMeD+54McHfxHF @QOc9RtpcGbBAwLsBxKvegEDmSd0C4P4/QMBrSYAuljBA5VFXYwc2GkDAXjsUir1gv1ZmWwJ5TUw/YfPb3lWpb79KPhN2CMoCv0P5Mx6uFzU/UAIG7gOFkb83ZlyfPICI\"\n" +
                "      } ]\n" +
                "    },\n" +
                "    \"meshCellSize\" : 64.0\n" +
                "  }";

        Assert.assertFalse("bad tile spec should have failed validation", isTileSpecValid(badTileJson));
    }

    private boolean isTileSpecValid(final String tileSpecJson) {

        boolean isValid = false;

        final WarpedTileSpecValidator validator = new WarpedTileSpecValidator(false);
        final TileSpec tileSpec = TileSpec.fromJson(tileSpecJson);

        try {
            validator.validate(tileSpec);
            isValid = true;
        } catch (final Throwable t) {
            LOG.info("validation error thrown", t);
        }

        return isValid;
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpedTileSpecValidatorTest.class);
}
