"use client";

import { CollapsibleCard } from "@/components/ui/collapsible-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { LocatorDto } from "@/lib/locator-api";

interface LocatorRepositoryProps {
  locators: LocatorDto[];
  loading: boolean;
  onGenerate: () => void;
}

function getStrategyColor(strategy: string) {
  switch (strategy) {
    case "TESTID":
      return "bg-green-500";
    case "ROLE":
      return "bg-blue-500";
    case "LABEL":
      return "bg-purple-500";
    case "TEXT":
      return "bg-yellow-500";
    case "CSS":
      return "bg-orange-500";
    case "XPATH":
      return "bg-red-500";
    default:
      return "bg-gray-500";
  }
}

export function LocatorRepository({ locators, loading, onGenerate }: LocatorRepositoryProps) {
  return (
    <CollapsibleCard
      title={`Locator Repository (${locators.length})`}
      defaultOpen={locators.length > 0}
      action={
        <Button onClick={onGenerate} disabled={loading} size="sm">
          {loading ? "Generating..." : "Generate Locators"}
        </Button>
      }
    >
      {locators.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No locators generated yet. Click &quot;Generate Locators&quot; to create stable Playwright locators.
        </p>
      ) : (
        <div className="space-y-3">
          {locators.map((locator) => (
            <div key={locator.id} className="border rounded-lg p-4 space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{locator.elementName}</span>
                  <Badge variant="outline">{locator.elementType}</Badge>
                </div>
                <div className="flex items-center gap-2">
                  <Badge className={getStrategyColor(locator.strategy)}>{locator.strategy}</Badge>
                  <span className="text-sm font-medium">{locator.confidence}%</span>
                </div>
              </div>
              <div className="text-sm">
                <span className="text-muted-foreground">Preferred: </span>
                <code className="bg-muted px-2 py-1 rounded text-xs">{locator.preferredLocator}</code>
              </div>
              {locator.fallbackLocators.length > 0 && (
                <div className="text-sm">
                  <span className="text-muted-foreground">Fallbacks: </span>
                  <div className="flex flex-wrap gap-1 mt-1">
                    {locator.fallbackLocators.map((fb, i) => (
                      <code key={i} className="bg-muted px-2 py-1 rounded text-xs">{fb}</code>
                    ))}
                  </div>
                </div>
              )}
              <p className="text-xs text-muted-foreground">{locator.reason}</p>
            </div>
          ))}
        </div>
      )}
    </CollapsibleCard>
  );
}
