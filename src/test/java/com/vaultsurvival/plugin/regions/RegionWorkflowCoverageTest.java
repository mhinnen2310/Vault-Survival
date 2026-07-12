package com.vaultsurvival.plugin.regions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionWorkflowCoverageTest {
    @Test void everyRequiredWorkflowUsesOneSharedVisualizationType(){assertEquals(RegionWorkflowCoverage.Workflow.values().length,RegionWorkflowCoverage.all().size());for(var workflow:RegionWorkflowCoverage.Workflow.values())assertNotNull(RegionWorkflowCoverage.type(workflow),workflow.name());}
}
