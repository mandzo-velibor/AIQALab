"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

interface CollapsibleCardProps {
  title: React.ReactNode;
  action?: React.ReactNode;
  defaultOpen?: boolean;
  className?: string;
  /**
   * Rendered in the header, outside the collapsible region.
   *
   * For the empty state. The body of a collapsed card is not rendered at all, so a
   * "nothing here yet, do this instead" message placed in the body is invisible in
   * precisely the situation it was written for — the user has to guess that the card is
   * expandable before they can read the instruction telling them what to do.
   */
  subtitle?: React.ReactNode;
  children: React.ReactNode;
}

export function CollapsibleCard({
  title,
  action,
  defaultOpen = true,
  className,
  subtitle,
  children,
}: CollapsibleCardProps) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <Card className={cn("md:col-span-2 lg:col-span-3", className)}>
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <Button
          variant="ghost"
          size="sm"
          className="gap-1 px-0 font-semibold leading-none tracking-tight"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
        >
          {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          <CardTitle>{title}</CardTitle>
        </Button>
        <div className="flex min-w-0 flex-1 flex-col">
          {subtitle && (
            <p className="truncate text-xs text-muted-foreground">{subtitle}</p>
          )}
        </div>
        {action && <div className="flex items-center gap-2">{action}</div>}
      </CardHeader>
      {open && <CardContent>{children}</CardContent>}
    </Card>
  );
}
