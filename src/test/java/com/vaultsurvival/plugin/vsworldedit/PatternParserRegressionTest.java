package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PatternParserRegressionTest {
    private PatternParser parser;
    @BeforeEach void setUp(){parser=new PatternParser(new MaterialResolver(true,false),true,true,32,BlockPattern.Mode.RANDOM);}
    @Test void setAirParses(){assertEquals(Material.AIR,parser.parse("air").pattern().materialAt(0,0,0,new Random()));}
    @Test void numericZeroParsesAsAir(){assertEquals(Material.AIR,parser.parse("0").pattern().materialAt(0,0,0,new Random()));}
    @Test void weightedRandomParses(){var result=parser.parse("70%stone,30%cobblestone");assertTrue(result.valid());assertEquals(BlockPattern.Mode.WEIGHTED_RANDOM,result.pattern().mode());}
    @Test void twoMaterialGridIsChecker(){var pattern=parser.parse("grid:stone,cobblestone").pattern();assertEquals(Material.STONE,pattern.materialAt(0,0,0,new Random()));assertEquals(Material.COBBLESTONE,pattern.materialAt(1,0,0,new Random()));assertEquals(Material.COBBLESTONE,pattern.materialAt(0,0,1,new Random()));assertEquals(Material.STONE,pattern.materialAt(1,0,1,new Random()));}
    @Test void threeMaterialGridIsDeterministic(){var pattern=parser.parse("grid:stone,cobblestone,andesite").pattern();for(int x=-5;x<5;x++)assertEquals(pattern.materialAt(x,7,3,new Random(1)),pattern.materialAt(x,7,3,new Random(99)));}
    @Test void legacyWeightsWithMaterialsParse(){assertTrue(parser.parse("50,50 stone,cobblestone").valid());}
    @Test void invalidMaterialOffersSuggestion(){var result=parser.parse("ston");assertFalse(result.valid());assertTrue((result.suggestion()==null?result.error():result.suggestion()).toLowerCase().contains("stone"));}
    @Test void airPatternAdvertisesUndoRelevantAir(){assertTrue(parser.parse("air").pattern().containsAir());}
}
