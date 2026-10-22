import React from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * 筛选条件放 URL。
 *
 * 后台的一次排查往往是「筛出这一批 → 点开一条 → 处理 → 回列表 → 再点下一条」。
 * 筛选条件如果只活在组件 state 里，回退或刷新就全没了，运营要重新选一遍五个下拉框；
 * 更糟的是他没法把「这一屏」发给同事看 —— 而后台里一半的沟通就是发链接。
 *
 * 所以：筛选、翻页、当前打开的那一条，全部进 query。
 */
export function useQ() {
  const [sp, setSp] = useSearchParams();

  const get = React.useCallback((k: string, dflt = '') => sp.get(k) ?? dflt, [sp]);
  const int = React.useCallback((k: string, dflt: number) => {
    const v = sp.get(k);
    const n = v === null ? NaN : Number(v);
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : dflt;
  }, [sp]);

  /**
   * @param keepPage 改筛选条件时默认回到第 1 页 —— 停在第 7 页看一个只有 2 页的结果，
   *                 页面是空的，而空列表在运营眼里等同于「没这条数据」。
   */
  const set = React.useCallback((patch: Record<string, string | number | undefined | null>,
                                 keepPage = false) => {
    setSp(prev => {
      const next = new URLSearchParams(prev);
      for (const [k, v] of Object.entries(patch)) {
        if (v === undefined || v === null || v === '') next.delete(k);
        else next.set(k, String(v));
      }
      if (!keepPage && !('page' in patch)) next.delete('page');
      return next;
    }, { replace: true });
  }, [setSp]);

  /** 清空筛选，但保留分页尺寸这类「用惯了的」偏好。 */
  const reset = React.useCallback((keep: string[] = ['size']) => {
    setSp(prev => {
      const next = new URLSearchParams();
      for (const k of keep) { const v = prev.get(k); if (v) next.set(k, v); }
      return next;
    }, { replace: true });
  }, [setSp]);

  return { sp, get, int, set, reset };
}

/** 拼查询串。空值一律不出现在 URL 里，否则后端会把空串当成一个真的筛选值。 */
export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** 这一屏是不是带着筛选条件。空态的话术全靠它区分「还没有」和「没筛到」。 */
export function hasFilter(sp: URLSearchParams, ignore: string[] = ['page', 'size', 'sel', 'tab', 'course']) {
  for (const k of sp.keys()) if (!ignore.includes(k)) return true;
  return false;
}
