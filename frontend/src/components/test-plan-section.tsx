"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { TestScenarioDto } from "@/lib/testplan-api";

interface TestPlanSectionProps {
  scenarios: TestScenarioDto[];
  loading: boolean;
  onGenerate: () => void;
}

function getTypeColor(type: string) {
  switch (type) {
    case "positive":
      return "bg-green-500";
    case "negative":
      return "bg-red-500";
    case "validation":
      return "bg-yellow-500";
    case "security":
      return "bg-purple-500";
    case "reliability":
      return "bg-blue-500";
    default:
      return "bg-gray-500";
  }
}

function getPriorityColor(priority: string) {
  switch (priority) {
    case "HIGH":
      return "destructive";
    case "MEDIUM":
      return "default";
    case "LOW":
      return "secondary";
    default:
      return "outline";
  }
}

export function TestPlanSection({ scenarios, loading, onGenerate }: TestPlanSectionProps) {
  return (
    <Card className="md:col-span-2 lg:col-span-3">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>AI Test Plan ({scenarios.length} scenarios)</CardTitle>
        <Button onClick={onGenerate} disabled={loading} size="sm">
          {loading ? "Generating..." : "Generate Test Plan"}
        </Button>
      </CardHeader>
      <CardContent>
        {scenarios.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No test plan generated yet. Click &quot;Generate Test Plan&quot; to create AI-powered test scenarios.
          </p>
        ) : (
          <div className="space-y-3">
            {scenarios.map((scenario) => (
              <div key={scenario.id} className="border rounded-lg p-4 space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{scenario.name}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge className={getTypeColor(scenario.type)}>{scenario.type}</Badge>
                    <Badge variant={getPriorityColor(scenario.priority)}>{scenario.priority}</Badge>
                  </div>
                </div>
                {scenario.description && (
                  <p className="text-sm text-muted-foreground">{scenario.description}</p>
                )}
                {scenario.steps.length > 0 && (
                  <div className="text-sm">
                    <span className="text-muted-foreground font-medium">Steps:</span>
                    <ol className="list-decimal list-inside mt-1 space-y-1">
                      {scenario.steps.map((step, i) => (
                        <li key={i} className="text-xs">{step}</li>
                      ))}
                    </ol>
                  </div>
                )}
                {scenario.requiredElements.length > 0 && (
                  <div className="text-sm">
                    <span className="text-muted-foreground font-medium">Required Elements:</span>
                    <div className="flex flex-wrap gap-1 mt-1">
                      {scenario.requiredElements.map((elem, i) => (
                        <Badge key={i} variant="outline" className="text-xs">{elem}</Badge>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
