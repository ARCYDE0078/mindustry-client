package sonkaextras;

import arc.math.Rand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Максимум суммарного DPS по растру против точного перебора кандидатов (центры и точки пересечения окружностей). */
public class TurretRangesPeakTest{

    /** Максимум перебором: сумма весов кругов, накрывающих точку, достигается в центре круга или рядом с точкой пересечения двух окружностей. */
    static float brute(float[] x, float[] y, float[] r, float[] w, int n){
        float best = 0f;
        for(int i = 0; i < n; i++){
            best = Math.max(best, at(x[i], y[i], x, y, r, w, n));
            for(int j = i + 1; j < n; j++){
                double dx = x[j] - x[i], dy = y[j] - y[i], d = Math.sqrt(dx * dx + dy * dy);
                if(d < 1e-6 || d > r[i] + r[j] || d < Math.abs(r[i] - r[j])) continue;
                double a = (r[i] * r[i] - r[j] * r[j] + d * d) / (2 * d), h = Math.sqrt(Math.max(0, r[i] * r[i] - a * a));
                double mx = x[i] + a * dx / d, my = y[i] + a * dy / d;
                for(int s = -1; s <= 1; s += 2){
                    double px = mx + s * h * dy / d, py = my - s * h * dx / d;
                    //во все 16 сторон чуть от точки пересечения: одна из четырёх ячеек вокруг неё - внутри обоих кругов (самая тяжёлая)
                    for(int k = 0; k < 16; k++){
                        double ang = k * Math.PI / 8;
                        best = Math.max(best, at((float)(px + 0.02 * Math.cos(ang)), (float)(py + 0.02 * Math.sin(ang)), x, y, r, w, n));
                    }
                }
            }
        }
        return best;
    }

    static float at(float px, float py, float[] x, float[] y, float[] r, float[] w, int n){
        float sum = 0f;
        for(int i = 0; i < n; i++){
            float dx = px - x[i], dy = py - y[i];
            if(dx * dx + dy * dy <= r[i] * r[i]) sum += w[i];
        }
        return sum;
    }

    @Test
    void singleAndEmpty(){
        assertEquals(0f, TurretRanges.peakOfCircles(new float[0], new float[0], new float[0], new float[0], 0));
        assertEquals(42f, TurretRanges.peakOfCircles(new float[]{100}, new float[]{100}, new float[]{50}, new float[]{42}, 1));
    }

    @Test
    void disjointCirclesTakeTheHeaviest(){
        float v = TurretRanges.peakOfCircles(new float[]{0, 1000}, new float[]{0, 0}, new float[]{80, 80}, new float[]{30, 70}, 2);
        assertEquals(70f, v, 1e-3f);
    }

    @Test
    void overlapSumsWeights(){
        //два круга накрывают друг друга: в линзе 30+70
        float v = TurretRanges.peakOfCircles(new float[]{0, 100}, new float[]{0, 0}, new float[]{80, 80}, new float[]{30, 70}, 2);
        assertEquals(100f, v, 1e-3f);
    }

    @Test
    void matchesBruteForceOnRandomSets(){
        Rand rand = new Rand(7);
        for(int round = 0; round < 40; round++){
            int n = 2 + rand.random(0, 30);
            float[] x = new float[n], y = new float[n], r = new float[n], w = new float[n];
            for(int i = 0; i < n; i++){
                x[i] = rand.random(0f, 800f);
                y[i] = rand.random(0f, 800f);
                r[i] = rand.random(40f, 220f);
                w[i] = rand.random(5f, 200f);
            }
            float exact = brute(x, y, r, w, n), raster = TurretRanges.peakOfCircles(x, y, r, w, n);
            //сверху: растр берёт настоящие точки (центры клеток), так что завысить максимум не может
            assertTrue(raster <= exact + 1e-2f, "round " + round + ": raster " + raster + " > exact " + exact);
            //снизу: любая точка, лежащая глубже 6 ед. внутри кругов, имеет центр клетки (клетка 8 ед., до центра <= 5,66) внутри тех же кругов -
            //растр обязан найти хотя бы это (тонкие «щели» уже клетки он вправе пропустить)
            float[] shrunk = new float[n];
            for(int i = 0; i < n; i++) shrunk[i] = r[i] - 6f;
            float safe = brute(x, y, shrunk, w, n);
            assertTrue(raster >= safe - 1e-2f, "round " + round + ": raster " + raster + " < safe " + safe + " (exact " + exact + ")");
        }
    }
}
