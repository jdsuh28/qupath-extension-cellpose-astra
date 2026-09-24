package qupath.ext.astra;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AstraCellposeOverlapTest {
    @Test
    void residualCellOverlapPreservesBothNuclei() {
        PathCellObject first = cell(0, 0, 20, 10, 2, 2, 4, 4);
        PathCellObject second = cell(5, 0, 20, 10, 8, 2, 4, 4);
        List<PathObject> corrected =
                AstraCellpose2D.enforceZeroPositiveAreaCellOverlap(List.of(first, second));
        assertEquals(2, corrected.size());
        PathCellObject a = (PathCellObject) corrected.get(0);
        PathCellObject b = (PathCellObject) corrected.get(1);
        assertTrue(a.getROI().getGeometry().covers(first.getNucleusROI().getGeometry()));
        assertTrue(b.getROI().getGeometry().covers(second.getNucleusROI().getGeometry()));
        assertEquals(0.0,
                a.getROI().getGeometry().intersection(b.getROI().getGeometry()).getArea());
    }

    @Test
    void overlappingNucleiRemainFatal() {
        PathCellObject first = cell(0, 0, 20, 10, 2, 2, 6, 4);
        PathCellObject second = cell(5, 0, 20, 10, 6, 2, 6, 4);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AstraCellpose2D.enforceZeroPositiveAreaCellOverlap(List.of(first, second)));
        assertTrue(error.getMessage().contains("overlapping nuclei"));
    }

    private static PathCellObject cell(double cx, double cy, double cw, double ch,
                                       double nx, double ny, double nw, double nh) {
        ImagePlane plane = ImagePlane.getDefaultPlane();
        return (PathCellObject) PathObjects.createCellObject(
                ROIs.createRectangleROI(cx, cy, cw, ch, plane),
                ROIs.createRectangleROI(nx, ny, nw, nh, plane), null, null);
    }
}
