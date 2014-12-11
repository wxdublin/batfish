package batfish.representation.juniper;

import java.util.List;

public class PolicyStatementSetCommunitySetLine extends PolicyStatement_SetLine {

   private static final long serialVersionUID = 1L;
   
   private List<String> _communities;
   
   /* ------------------------------ Constructor ----------------------------*/
   public PolicyStatementSetCommunitySetLine(List<String> c) {
      _communities = c;
   }
   
   /* ----------------------------- Other Methods ---------------------------*/
   
   /* ---------------------------- Getters/Setters --------------------------*/
   public List<String> get_communities() {
      return _communities;
   }
   
   /* --------------------------- Inherited Methods -------------------------*/
   @Override
   public PolicyStatement_SetType getType() {
      return PolicyStatement_SetType.COMMUNITY_SET;
   }  
   
}