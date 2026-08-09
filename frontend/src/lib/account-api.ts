import { API_BASE_URL } from "@/lib/config";
import { httpRequest } from "@/lib/http";

export interface BudgetInfo {
  policy: string;
  plan: string | null;
  limit: number;
  used: number;
  remaining: number;
  hardStop: boolean;
  softExceeded: boolean;
  updatedAt: string;
}

export interface UsageByOperation {
  operation: string;
  calls: number;
  tokens: number;
}

export interface UsageRecord {
  operation: string;
  provider: string;
  model: string | null;
  mode: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimated: boolean;
  operationId: string;
  createdAt: string;
}

export interface AccountUsage {
  budget: BudgetInfo;
  breakdown: UsageByOperation[];
  recent: UsageRecord[];
}

export async function getAccountUsage(): Promise<AccountUsage> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/account/usage`);
  return res.json();
}

export async function getBudgetPolicy(): Promise<BudgetInfo> {
  const res = await httpRequest(`${API_BASE_URL}/api/v1/account/budget-policy`);
  return res.json();
}
