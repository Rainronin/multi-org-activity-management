import { ElMessageBox } from 'element-plus'

/** 保留草稿的原版本；只有用户明确确认后才基于已核对的版本保存。 */
export async function confirmDraftSave(baseVersion: number, checkedVersion: number): Promise<number | null> {
  if (baseVersion === checkedVersion) return checkedVersion
  try {
    await ElMessageBox.confirm(
      '活动已更新，当前草稿基于旧版本。请核对最新内容；继续保存将覆盖对应字段的最新内容。',
      '活动已更新',
      { confirmButtonText: '继续保存', cancelButtonText: '保留草稿', type: 'warning' },
    )
    // 弹窗期间的刷新不能把已确认的版本再次换成更新版本。
    return checkedVersion
  } catch {
    return null
  }
}
