package com.hl.discipline;

import java.util.ArrayList;
import java.util.List;

/** Edits a draft only. Saving the place remains a separate user action. */
final class BoundaryEditing {
  static List<double[]> remove(List<double[]> points, int index) {
    if (points.size() <= 3) throw new IllegalArgumentException("范围至少保留 3 个边界点");
    if (index < 0 || index >= points.size()) throw new IllegalArgumentException("请先选中一个边界点");
    List<double[]> result = new ArrayList<>();
    for (int i = 0; i < points.size(); i++) if (i != index) result.add(points.get(i).clone());
    if (!new Rules.PolygonArea(result).valid())
      throw new IllegalArgumentException("删除后范围过小或边界交叉，请选择其他点或重画");
    return result;
  }
}
