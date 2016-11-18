package org.batfish.datamodel;

import java.util.List;

import org.batfish.common.util.ComparableStructure;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Ip6AccessList extends ComparableStructure<String> {

   private static final String LINES_VAR = "lines";

   private static final long serialVersionUID = 1L;

   static boolean bothNullOrSameName(Ip6AccessList a, Ip6AccessList b) {
      if (a == null && b == null) {
         return true;
      }
      else if (a != null && b != null) {
         return a.getName().equals(b.getName());
      }
      else {
         return false;
      }
   }

   static boolean bothNullOrUnorderedEqual(Ip6AccessList a, Ip6AccessList b) {
      if (a == null && b == null) {
         return true;
      }
      else if (a != null && b != null) {
         return a.unorderedEqual(b);
      }
      else {
         return false;
      }
   }

   private List<Ip6AccessListLine> _lines;

   @JsonCreator
   public Ip6AccessList(@JsonProperty(NAME_VAR) String name) {
      super(name);
   }

   public Ip6AccessList(String name, List<Ip6AccessListLine> lines) {
      super(name);
      _lines = lines;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj) {
         return true;
      }
      Ip6AccessList other = (Ip6AccessList) obj;
      return other._lines.equals(_lines);
   }

   public FilterResult filter(Flow6 flow) {
      for (int i = 0; i < _lines.size(); i++) {
         Ip6AccessListLine line = _lines.get(i);
         if (line.matches(flow)) {
            return new FilterResult(i, line.getAction());
         }
      }
      return new FilterResult(null, LineAction.REJECT);
   }

   @JsonProperty(LINES_VAR)
   public List<Ip6AccessListLine> getLines() {
      return _lines;
   }

   private boolean noDenyOrLastDeny(Ip6AccessList acl) {
      int count = 0;
      for (Ip6AccessListLine line : acl.getLines()) {
         if (line.getAction() == LineAction.REJECT
               && count < acl.getLines().size() - 1) {
            return false;
         }
         count++;
      }
      return true;
   }

   @JsonProperty(LINES_VAR)
   public void setLines(List<Ip6AccessListLine> lines) {
      _lines = lines;
   }

   @Override
   public String toString() {
      String output = super.toString() + "\n" + "Identifier: " + _key;
      for (Ip6AccessListLine line : _lines) {
         output += "\n" + line;
      }
      return output;
   }

   public boolean unorderedEqual(Object obj) {
      if (this == obj) {
         return true;
      }
      if (this.equals(obj)) {
         return true;
      }
      Ip6AccessList other = (Ip6AccessList) obj;
      if (this.getLines().size() != other.getLines().size()) {
         return false;
      }
      // Unordered check is valid only if there is no deny OR if there is only
      // one, at the
      // end, in both lists.
      if (!noDenyOrLastDeny(this) || !noDenyOrLastDeny(other)) {
         return false;
      }
      for (Ip6AccessListLine line : this.getLines()) {
         if (!other.getLines().contains(line)) {
            return false;
         }
      }
      return true;
   }
}
