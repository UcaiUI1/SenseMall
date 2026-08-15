import { http } from '@/utils/http'
import type { CommonPage, PageParam } from '@/types/common'

/** 积分变动明细 */
export interface IntegrationHistoryItem {
  id: number
  createTime: string
  /** 0 增加；1 减少 */
  changeType: number
  changeCount: number
  operateNote: string
  /** 0购物 1管理员 2签到 3注册 4订单抵扣 5取消退回 */
  sourceType: number
  orderId: number
  balanceAfter: number
}

/** 签到状态 */
export interface IntegrationCheckinStatus {
  checked: boolean
  streak: number
  points: number
}

/** 每日签到 */
export const checkinAPI = () => {
  return http<number>({
    method: 'POST',
    url: '/member/integration/checkin',
  })
}

/** 签到状态 */
export const getCheckinStatusAPI = () => {
  return http<IntegrationCheckinStatus>({
    method: 'GET',
    url: '/member/integration/checkin/status',
  })
}

/** 积分明细 */
export const getIntegrationHistoryAPI = (params: PageParam) => {
  return http<CommonPage<IntegrationHistoryItem>>({
    method: 'GET',
    url: '/member/integration/history',
    params,
  })
}
